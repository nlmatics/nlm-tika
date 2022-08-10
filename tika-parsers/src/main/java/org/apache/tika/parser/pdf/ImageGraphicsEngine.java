/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.pdf;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.MissingImageReaderException;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.BoundedInputStream;
import org.apache.tika.io.IOExceptionWithCause;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Copied nearly verbatim from PDFBox
 */
class ImageGraphicsEngine extends PDFGraphicsStreamEngine {

    //We're currently copying images to byte[].  We should
    //limit the length to avoid OOM on crafted files.
    private static final long MAX_IMAGE_LENGTH_BYTES = 100*1024*1024;

    private static final List<String> JPEG = Arrays.asList(
            COSName.DCT_DECODE.getName(),
            COSName.DCT_DECODE_ABBREVIATION.getName());


    private static final List<String> JP2 =
            Arrays.asList(COSName.JPX_DECODE.getName());

    private static final List<String> JB2 = Arrays.asList(
            COSName.JBIG2_DECODE.getName());

    //TODO: parameterize this ?
    private boolean useDirectJPEG = false;

    final List<IOException> exceptions = new ArrayList<>();
    private final EmbeddedDocumentExtractor embeddedDocumentExtractor;
    private final PDFParserConfig pdfParserConfig;
    private final Map<COSStream, Integer> processedInlineImages;
    private final AtomicInteger imageCounter;
    private final Metadata parentMetadata;
    private final XHTMLContentHandler xhtml;
    private final ParseContext parseContext;
    private Point2D currentPoint = new Point2D.Float(0, 0);

    //TODO: this is an embarrassment of an initializer...fix
    protected ImageGraphicsEngine(PDPage page, EmbeddedDocumentExtractor embeddedDocumentExtractor,
                                  PDFParserConfig pdfParserConfig, Map<COSStream, Integer> processedInlineImages,
                                  AtomicInteger imageCounter, XHTMLContentHandler xhtml, Metadata parentMetadata,
                                  ParseContext parseContext) {
        super(page);
        this.embeddedDocumentExtractor = embeddedDocumentExtractor;
        this.pdfParserConfig = pdfParserConfig;
        this.processedInlineImages = processedInlineImages;
        this.imageCounter = imageCounter;
        this.xhtml = xhtml;
        this.parentMetadata = parentMetadata;
        this.parseContext = parseContext;
    }

    void run() throws IOException {
        PDPage page = getPage();

        //TODO: is there a better way to do this rather than reprocessing the page
        //can we process the text and images in one go?
        processPage(page);
        PDResources res = page.getResources();
        if (res == null) {
            return;
        }

        for (COSName name : res.getExtGStateNames()) {
            PDExtendedGraphicsState extendedGraphicsState = res.getExtGState(name);
            if (extendedGraphicsState != null) {
                PDSoftMask softMask = extendedGraphicsState.getSoftMask();

                if (softMask != null) {
                    try {
                        PDTransparencyGroup group = softMask.getGroup();

                        if (group != null) {
                            // PDFBOX-4327: without this line NPEs will occur
                            res.getExtGState(name).copyIntoGraphicsState(getGraphicsState());

                            processSoftMask(group);
                        }
                    } catch (IOException e) {
                        handleCatchableIOE(e);
                    }
                }
            }
        }
    }
    private String getStrokeStyle() {
        float[] components = getGraphicsState().getStrokingColor().getComponents();
        PDRectangle pageDim = this.getPage().getBBox();
        float[] rgb = {0, 0, 0};
        for (int i=0; i < components.length; i++) {
            rgb[i] = components[i]*255;
        }
        String color = "rgb(" + StringUtils.join(ArrayUtils.toObject(rgb), ",") + ")";
        String styleStr = "fill:none;" + "stroke-width:" + this.getGraphicsState().getLineWidth() + ";stroke:" + color;
        return styleStr;
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        int imageNumber = 0;
        if (pdImage instanceof PDImageXObject) {
            if (pdImage.isStencil()) {
                processColor(getGraphicsState().getNonStrokingColor());
            }

            PDImageXObject xobject = (PDImageXObject) pdImage;
            Integer cachedNumber = processedInlineImages.get(xobject.getCOSObject());
            if (cachedNumber != null && pdfParserConfig.getExtractUniqueInlineImagesOnly()) {
                // skip duplicate image
                return;
            }
            if (cachedNumber == null) {
                imageNumber = imageCounter.getAndIncrement();
                processedInlineImages.put(xobject.getCOSObject(), imageNumber);
            }
        } else {
            imageNumber = imageCounter.getAndIncrement();
        }
        //TODO: should we use the hash of the PDImage to check for seen
        //For now, we're relying on the cosobject, but this could lead to
        //duplicates if the pdImage is not a PDImageXObject?
        try {
            processImage(pdImage, imageNumber);
        } catch (TikaException|SAXException e) {
            throw new IOExceptionWithCause(e);
        } catch (IOException e) {
            handleCatchableIOE(e);
        }
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3)
            throws IOException {
        String styleStr = this.getStrokeStyle();
        PDRectangle pageDim = this.getPage().getBBox();
        double x = p3.getX();
        double y = pageDim.getUpperRightY() - p3.getY();
        double width = p1.getX() - p0.getX();
        double height = p1.getY() - p3.getY();
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "x", "x", "CDATA", String.valueOf(x));
        attrs.addAttribute("", "y", "y", "CDATA", String.valueOf(y));
        attrs.addAttribute("", "width", "width", "CDATA", String.valueOf(width));
        attrs.addAttribute("", "height", "height", "CDATA", String.valueOf(height));
        attrs.addAttribute("", "style", "style", "CDATA", styleStr);
        try {
            xhtml.startElement("rect", attrs);
            xhtml.characters("");
            xhtml.endElement("rect");
        } catch (SAXException e) {
            throw new IOExceptionWithCause(e);
        }
    }

    @Override
    public void clip(int windingRule) throws IOException {

    }

    @Override
    public void moveTo(float x, float y) throws IOException {
        this.currentPoint.setLocation(x, y);
    }

    @Override
    public void lineTo(float x, float y) throws IOException {

        String styleStr = this.getStrokeStyle();
        AttributesImpl attrs = new AttributesImpl();
        PDRectangle pageDim = this.getPage().getBBox();
        double offset = pageDim.getHeight();
        double x1 = currentPoint.getX();
        double y1 = offset - currentPoint.getY();
        double x2 = x;
        double y2 = offset - y;

        attrs.addAttribute("", "x1", "x1", "CDATA", String.valueOf(x1));
        attrs.addAttribute("", "y1", "y1", "CDATA", String.valueOf(y1));
        attrs.addAttribute("", "x2", "x2", "CDATA", String.valueOf(x2));
        attrs.addAttribute("", "y2", "y2", "CDATA", String.valueOf(y2));
        attrs.addAttribute("", "style", "style", "CDATA", styleStr);
        this.currentPoint.setLocation(x, y);
        try {
            xhtml.startElement("line", attrs);
            xhtml.characters("");
            xhtml.endElement("line");
        } catch (SAXException e) {
            throw new IOExceptionWithCause(e);
        }
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3)
            throws IOException {
        this.currentPoint.setLocation(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() throws IOException {
        return this.currentPoint;
    }

    @Override
    public void closePath() throws IOException {

    }

    @Override
    public void endPath() throws IOException {

    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix,
                             PDFont font,
                             int code,
                             String unicode,
                             Vector displacement) throws IOException {

        RenderingMode renderingMode = getGraphicsState().getTextState().getRenderingMode();
        if (renderingMode.isFill()) {
            processColor(getGraphicsState().getNonStrokingColor());
        }

        if (renderingMode.isStroke()) {
            processColor(getGraphicsState().getStrokingColor());
        }
    }

    @Override
    public void strokePath() throws IOException {
        processColor(getGraphicsState().getStrokingColor());
    }

    @Override
    public void fillPath(int windingRule) throws IOException {
        processColor(getGraphicsState().getNonStrokingColor());
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException {
        processColor(getGraphicsState().getNonStrokingColor());
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException {

    }

    // find out if it is a tiling pattern, then process that one
    private void processColor(PDColor color) throws IOException {
        if (color.getColorSpace() instanceof PDPattern) {
            PDPattern pattern = (PDPattern) color.getColorSpace();
            PDAbstractPattern abstractPattern = pattern.getPattern(color);

            if (abstractPattern instanceof PDTilingPattern) {
                processTilingPattern((PDTilingPattern) abstractPattern, null, null);
            }
        }
    }

    private void processImage(PDImage pdImage, int imageNumber) throws IOException, TikaException, SAXException {
        //this is the metadata for this particular image
        System.out.println("processing embedded image....");
        Metadata metadata = new Metadata();
        String suffix = getSuffix(pdImage, metadata);
        String fileName = "image" + imageNumber + "." + suffix;


        AttributesImpl attr = new AttributesImpl();
        attr.addAttribute("", "src", "src", "CDATA", "embedded:" + fileName);
        attr.addAttribute("", "alt", "alt", "CDATA", fileName);
        xhtml.startElement("img", attr);
        xhtml.endElement("img");


        metadata.set(Metadata.RESOURCE_NAME_KEY, fileName);
        metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                TikaCoreProperties.EmbeddedResourceType.INLINE.toString());

        if (embeddedDocumentExtractor.shouldParseEmbedded(metadata)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (pdImage instanceof PDImageXObject) {
                PDMetadataExtractor.extract(((PDImageXObject) pdImage).getMetadata(),
                        metadata, parseContext);
            }
            //extract the metadata contained outside of the image
            try {
                writeToBuffer(pdImage, suffix, useDirectJPEG, buffer);
            }  catch (MissingImageReaderException e) {
                EmbeddedDocumentUtil.recordException(e, parentMetadata);
                return;
            } catch (IOException e) {
                EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                return;
            }
            try (InputStream embeddedIs = TikaInputStream.get(buffer.toByteArray())) {
                embeddedDocumentExtractor.parseEmbedded(
                        embeddedIs,
                        new EmbeddedContentHandler(xhtml),
                        metadata, false);
            }
        }

    }

    private String getSuffix(PDImage pdImage, Metadata metadata) throws IOException {
        String suffix = pdImage.getSuffix();

        if (suffix == null || suffix.equals("png")) {
            metadata.set(Metadata.CONTENT_TYPE, "image/png");
            suffix = "png";
        } else if (suffix.equals("jpg")) {
            metadata.set(Metadata.CONTENT_TYPE, "image/jpeg");
        } else if (suffix.equals("tiff")) {
            metadata.set(Metadata.CONTENT_TYPE, "image/tiff");
            suffix = "tif";
        } else if (suffix.equals("jpx")) {
            metadata.set(Metadata.CONTENT_TYPE, "image/jp2");
            // use jp2 suffix for file because jpx not known by windows
            suffix = "jp2";
        } else if (suffix.equals("jb2")) {
            //PDFBox resets suffix to png when image's suffix == jb2
            metadata.set(
                    Metadata.CONTENT_TYPE, "image/x-jbig2");
        } else {
            //TODO: determine if we need to add more image types
//                    throw new RuntimeException("EXTEN:" + extension);
        }
        if (hasMasks(pdImage)) {
            // TIKA-3040, PDFBOX-4771: can't save ARGB as JPEG
            suffix = "png";
        }
        return suffix;
    }

    void handleCatchableIOE(IOException e) throws IOException {
        if (pdfParserConfig.getCatchIntermediateIOExceptions()) {
            if (e.getCause() instanceof SAXException && e.getCause().getMessage() != null &&
                    e.getCause().getMessage().contains("Your document contained more than")) {
                //TODO -- is there a cleaner way of checking for:
                // WriteOutContentHandler.WriteLimitReachedException?
                throw e;
            }

            String msg = e.getMessage();
            if (msg == null) {
                msg = "IOException, no message";
            }
            parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, msg);
            exceptions.add(e);
        } else {
            throw e;
        }
    }

    List<IOException> getExceptions() {
        return exceptions;
    }

    //nearly directly copied from PDFBox ExtractImages
    private static void writeToBuffer(PDImage pdImage, String suffix, boolean directJPEG, OutputStream out)
            throws IOException, TikaException {

        if ("jpg".equals(suffix)) {

            String colorSpaceName = pdImage.getColorSpace().getName();
            if (directJPEG ||
                    (PDDeviceGray.INSTANCE.getName().equals(colorSpaceName) ||
                            PDDeviceRGB.INSTANCE.getName().equals(colorSpaceName))) {
                // RGB or Gray colorspace: get and write the unmodified JPEG stream
                InputStream data = pdImage.createInputStream(JPEG);
                try {
                    copyUpToMaxLength(data, out);
                } finally {
                    IOUtils.closeQuietly(data);
                }
            } else {
                BufferedImage image = pdImage.getImage();
                if (image != null) {
                    // for CMYK and other "unusual" colorspaces, the JPEG will be converted
                    ImageIOUtil.writeImage(image, suffix, out);
                }
            }
        } else if ("jp2".equals(suffix)) {
            String colorSpaceName = pdImage.getColorSpace().getName();
            if (directJPEG ||
                    !hasMasks(pdImage) &&
                            (PDDeviceGray.INSTANCE.getName().equals(colorSpaceName) ||
                                    PDDeviceRGB.INSTANCE.getName().equals(colorSpaceName))) {
                // RGB or Gray colorspace: get and write the unmodified JPEG2000 stream
                InputStream data = pdImage.createInputStream(JP2);
                try {
                    copyUpToMaxLength(data, out);
                } finally {
                    IOUtils.closeQuietly(data);
                }
            } else {
                // for CMYK and other "unusual" colorspaces, the image will be converted
                BufferedImage image = pdImage.getImage();
                if (image != null) {
                    // for CMYK and other "unusual" colorspaces, the JPEG will be converted
                    ImageIOUtil.writeImage(image, "jpeg2000", out);
                }
            }
        } else if ("tif".equals(suffix) && pdImage.getColorSpace().equals(PDDeviceGray.INSTANCE)) {
            BufferedImage image = pdImage.getImage();
            if (image == null) {
                return;
            }
            // CCITT compressed images can have a different colorspace, but this one is B/W
            // This is a bitonal image, so copy to TYPE_BYTE_BINARY
            // so that a G4 compressed TIFF image is created by ImageIOUtil.writeImage()
            int w = image.getWidth();
            int h = image.getHeight();
            BufferedImage bitonalImage = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
            // copy image the old fashioned way - ColorConvertOp is slower!
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bitonalImage.setRGB(x, y, image.getRGB(x, y));
                }
            }
            ImageIOUtil.writeImage(bitonalImage, suffix, out);
        } else if ("jb2".equals(suffix)) {
            InputStream data = pdImage.createInputStream(JB2);
            try {
                copyUpToMaxLength(data, out);
            } finally {
                IOUtils.closeQuietly(data);
            }
        } else {
            BufferedImage image = pdImage.getImage();
            if (image == null) {
                return;
            }
            ImageIOUtil.writeImage(image, suffix, out);
        }

        out.flush();
    }

    private static void copyUpToMaxLength(InputStream is, OutputStream os) throws IOException, TikaException {
        BoundedInputStream bis = new BoundedInputStream(MAX_IMAGE_LENGTH_BYTES, is);
        IOUtils.copy(bis, os);
        if (bis.hasHitBound()) {
            throw new TikaMemoryLimitException("Image size is larger than allowed (" + MAX_IMAGE_LENGTH_BYTES + ")");
        }

    }
    private static boolean hasMasks(PDImage pdImage) throws IOException {
        if (pdImage instanceof PDImageXObject) {
            PDImageXObject ximg = (PDImageXObject) pdImage;
            return ximg.getMask() != null || ximg.getSoftMask() != null;
        }
        return false;
    }
}
