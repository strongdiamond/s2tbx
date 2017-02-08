package org.esa.s2tbx.dataio.gdal.writer;

import org.esa.s2tbx.dataio.gdal.reader.plugins.MFFDriverProductReaderPlugIn;
import org.esa.s2tbx.dataio.gdal.writer.plugins.MFFDriverProductWriterPlugIn;

/**
 * @author Jean Coravu
 */
public class MFFDriverProductWriterTest extends AbstractDriverProductWriterTest {

    public MFFDriverProductWriterTest() {
        super("MFF", ".hdr", "Byte UInt16 Float32 CInt16 CFloat32", new MFFDriverProductReaderPlugIn(), new MFFDriverProductWriterPlugIn());
    }
}