/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox.filter;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSDictionary;
import org.w3c.dom.Element;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

/**
 * Decompresses data encoded using a DCT (discrete cosine transform)
 * technique based on the JPEG standard.
 *
 * This filter is called {@code DCTDecode} in the PDF Reference.
 *
 * @author John Hewson
 */
public final class DCTFilter implements Filter
{
    private static final Log LOG = LogFactory.getLog(DCTFilter.class);

    public void decode(InputStream input, OutputStream output,
                       COSDictionary options, int filterIndex) throws IOException
    {
        // find suitable image reader
        Iterator readers = ImageIO.getImageReadersByFormatName("JPEG");
        ImageReader reader = null;
        while(readers.hasNext()) {
            reader = (ImageReader)readers.next();
            if(reader.canReadRaster()) {
                break;
            }
        }

        if (reader == null)
        {
            throw new MissingImageReaderException("Cannot read JPEG image: " +
                    "a suitable JAI I/O image filter is not installed");
        }

        // I'd planned to use ImageReader#readRaster but it is buggy
        BufferedImage hack = ImageIO.read(input);
        Raster raster = hack.getRaster();

        // special handling for 4-component images
        if (raster.getNumBands() == 4)
        {
            // get APP14 marker
            Integer transform;
            try
            {
                transform = getAdobeTransform(reader.getImageMetadata(0));
            }
            catch (IIOException e)
            {
                // catches the error "Inconsistent metadata read from stream"
                // which seems to be present indicate a YCCK image, but who knows?
                LOG.warn("Inconsistent metadata read from JPEG stream");
                transform = 2; // YCCK
            }
            int colorTransform = transform != null ? transform : 0;

            // 0 = Unknown (RGB or CMYK), 1 = YCbCr, 2 = YCCK
            switch (colorTransform)
            {
                case 0: break; // already CMYK
                case 1: LOG.warn("YCbCr JPEGs not implemented"); break; // TODO YCbCr
                case 2: raster = fromYCCKtoCMYK(raster); break;
            }
        }
        else if (raster.getNumBands() == 3)
        {
            // BGR to RGB
            raster = fromBGRtoRGB(raster);
        }

        DataBufferByte dataBuffer = (DataBufferByte)raster.getDataBuffer();
        output.write(dataBuffer.getData());
    }

    public void encode(InputStream rawData, OutputStream result,
                       COSDictionary options, int filterIndex) throws IOException
    {
        LOG.warn("DCTFilter#encode is not implemented yet, skipping this stream.");
    }

    // reads the APP14 Adobe transform tag
    private Integer getAdobeTransform(IIOMetadata metadata)
    {
        Element tree = (Element)metadata.getAsTree("javax_imageio_jpeg_image_1.0");
        Element markerSequence = (Element)tree.getElementsByTagName("markerSequence").item(0);

        if (markerSequence.getElementsByTagName("app14Adobe") != null)
        {
            Element adobe = (Element)markerSequence.getElementsByTagName("app14Adobe").item(0);
            return Integer.parseInt(adobe.getAttribute("transform"));
        }
        return 0; // Unknown
    }

    // converts YCCK image to CMYK. YCCK is an equivalent encoding for
    // CMYK data, so no color management code is needed here, nor does the
    // PDF color space have to be consulted
    private WritableRaster fromYCCKtoCMYK(Raster raster) throws IOException
    {
        WritableRaster writableRaster = raster.createCompatibleWritableRaster();

        int[] value = new int[4];
        for (int y = 0, height = raster.getHeight(); y < height; y++)
        {
            for (int x = 0, width = raster.getWidth(); x < width; x++)
            {
                raster.getPixel(x, y, value);

                // 4-channels 0..255
                float Y = value[0];
                float Cb = value[1];
                float Cr = value[2];
                float K = value[3];

                // YCCK to RGB, see http://software.intel.com/en-us/node/442744
                int r = clamp(Y + 1.402f * Cr - 179.456f);
                int g = clamp(Y - 0.34414f * Cb - 0.71414f * Cr + 135.45984f);
                int b = clamp(Y + 1.772f * Cb - 226.816f);

                // naive RGB to CMYK
                int cyan = 255 - r;
                int magenta = 255 - g;
                int yellow = 255 - b;

                // update new raster
                value[0] = cyan;
                value[1] = magenta;
                value[2] = yellow;
                value[3] = (int)K;
                writableRaster.setPixel(x, y, value);
            }
        }
        return writableRaster;
    }

    // converts from BGR to RGB
    private WritableRaster fromBGRtoRGB(Raster raster) throws IOException
    {
        WritableRaster writableRaster = raster.createCompatibleWritableRaster();

        int[] bgr = new int[3];
        int[] rgb = new int[3];
        for (int y = 0, height = raster.getHeight(); y < height; y++)
        {
            for (int x = 0, width = raster.getWidth(); x < width; x++)
            {
                raster.getPixel(x, y, bgr);
                rgb[0] = bgr[2];
                rgb[1] = bgr[1];
                rgb[2] = bgr[0];
                writableRaster.setPixel(x, y, rgb);
            }
        }

        return writableRaster;
    }

    // clamps value to 0-255 range
    private int clamp(float value)
    {
        return (int)((value < 0) ? 0 : ((value > 255) ? 255 : value));
    }
}
