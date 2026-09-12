package tools.jackson.dataformat.javaprop.constraints;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;

import tools.jackson.databind.JsonNode;

import tools.jackson.dataformat.javaprop.JavaPropsFactory;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;
import tools.jackson.dataformat.javaprop.ModuleTestBase;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StreamReadConstraints#getMaxDocumentLength()} enforcement
 * in Properties parsing: since {@code java.util.Properties.load()} reads input
 * directly, this is done by counting characters it reads.
 *
 * @see <a href="https://github.com/FasterXML/jackson-dataformats-text/issues/638">[dataformats-text#638]</a>
 */
public class PropsDocumentLengthTest extends ModuleTestBase
{
    private final static int MAX_DOC_LEN = 10_000;

    private static JavaPropsFactory factoryWithDocLimit(long limit) {
        return JavaPropsFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(limit).build())
                .build();
    }

    private final JavaPropsMapper LIMITED_MAPPER = new JavaPropsMapper(factoryWithDocLimit(MAX_DOC_LEN));

    @Test
    public void testDocumentWithinLimit() throws Exception
    {
        _verifyReadable(LIMITED_MAPPER, _generateProps(MAX_DOC_LEN - 100));
    }

    @Test
    public void testDocumentExceedingLimit() throws Exception
    {
        final String doc = _generateProps(MAX_DOC_LEN + 500);
        _verifyDocTooLong(() -> LIMITED_MAPPER.readTree(doc));
        _verifyDocTooLong(() -> LIMITED_MAPPER.readTree(new StringReader(doc)));
        _verifyDocTooLong(() -> LIMITED_MAPPER.readTree(new ByteArrayInputStream(doc.getBytes(StandardCharsets.ISO_8859_1))));
        _verifyDocTooLong(() -> LIMITED_MAPPER.readTree(doc.getBytes(StandardCharsets.ISO_8859_1)));
        // Streaming access: parser construction already loads properties
        _verifyDocTooLong(() -> {
            try (JsonParser p = factoryWithDocLimit(MAX_DOC_LEN).createParser(ObjectReadContext.empty(), doc)) {
                while (p.nextToken() != null) { }
            }
        });
        // And sanity check: fine with default constraints
        _verifyReadable(newPropertiesMapper(), doc);
    }

    // Limit smaller than `Properties.load()` read buffer (8k): must still be enforced
    @Test
    public void testSmallLimit() throws Exception
    {
        JavaPropsMapper mapper = new JavaPropsMapper(factoryWithDocLimit(100));
        _verifyReadable(mapper, _generateProps(50));
        _verifyDocTooLong(() -> mapper.readTree(_generateProps(200)));
    }

    // Single value longer than limit
    @Test
    public void testLongValue() throws Exception
    {
        final String doc = "key=" + "x".repeat(MAX_DOC_LEN + 500) + "\n";
        _verifyDocTooLong(() -> LIMITED_MAPPER.readTree(doc));
        // (and it is document length, not String length, that fails)
        assertEquals(MAX_DOC_LEN + 500, newPropertiesMapper().readTree(doc).get("key").stringValue().length());
    }

    // No limit configured: nothing is counted or enforced
    @Test
    public void testNoLimit() throws Exception
    {
        JavaPropsFactory f = JavaPropsFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(-1L).build())
                .build();
        assertFalse(f.streamReadConstraints().hasMaxDocumentLength());
        _verifyReadable(new JavaPropsMapper(f), _generateProps(MAX_DOC_LEN * 3));
    }

    /*
    /**********************************************************************
    /* Helpers
    /**********************************************************************
     */

    private interface ThrowingRunnable { void run() throws Exception; }

    private void _verifyDocTooLong(ThrowingRunnable r) throws Exception
    {
        try {
            r.run();
            fail("expected StreamConstraintsException");
        } catch (StreamConstraintsException e) {
            verifyException(e, "Document length");
            verifyException(e, "exceeds the maximum allowed");
        }
    }

    private void _verifyReadable(JavaPropsMapper mapper, String doc) throws Exception
    {
        JsonNode n = mapper.readTree(doc);
        assertTrue(n.isObject() && n.size() > 0);
        assertEquals(n, mapper.readTree(new StringReader(doc)));
        assertEquals(n, mapper.readTree(new ByteArrayInputStream(doc.getBytes(StandardCharsets.ISO_8859_1))));
        assertEquals(n, mapper.readTree(doc.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static String _generateProps(int targetLen) {
        StringBuilder sb = new StringBuilder(targetLen + 32);
        int i = 0;
        while (sb.length() < targetLen) {
            sb.append("key").append(i++).append("=value\n");
        }
        return sb.toString();
    }
}
