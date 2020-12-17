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

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Utility class that overrides the {@link PDFTextStripper} functionality
 * to produce a semi-structured XHTML SAX events instead of a plain text
 * stream.
 */
class PDF2XHTML extends AbstractPDF2XHTML {


    /**
     * This keeps track of the pdf object ids for inline
     * images that have been processed.
     * If {@link PDFParserConfig#getExtractUniqueInlineImagesOnly()
     * is true, this will be checked before extracting an embedded image.
     * The integer keeps track of the inlineImageCounter for that image.
     * This integer is used to identify images in the markup.
     * 
     * This is used across the document.  To avoid infinite recursion
     * TIKA-1742, we're limiting the export to one image per page.
     */
    private Map<COSStream, Integer> processedInlineImages = new HashMap<>();
    private AtomicInteger inlineImageCounter = new AtomicInteger(0);
    PDF2XHTML(PDDocument document, ContentHandler handler, ParseContext context, Metadata metadata,
              PDFParserConfig config)
            throws IOException {
        super(document, handler, context, metadata, config);
    }

    /**
     * Converts the given PDF document (and related metadata) to a stream
     * of XHTML SAX events sent to the given content handler.
     *
     * @param document PDF document
     * @param handler  SAX content handler
     * @param metadata PDF metadata
     * @throws SAXException  if the content handler fails to process SAX events
     * @throws TikaException if there was an exception outside of per page processing
     */
    public static void process(
            PDDocument document, ContentHandler handler, ParseContext context, Metadata metadata,
            PDFParserConfig config)
            throws SAXException, TikaException {
        PDF2XHTML pdf2XHTML = null;
        try {
            // Extract text using a dummy Writer as we override the
            // key methods to output to the given content
            // handler.
            if (config.getDetectAngles()) {
                pdf2XHTML = new AngleDetectingPDF2XHTML(document, handler, context, metadata, config);
            } else {
                pdf2XHTML = new PDF2XHTML(document, handler, context, metadata, config);
            }
            config.configure(pdf2XHTML);

            pdf2XHTML.writeText(document, new Writer() {
                @Override
                public void write(char[] cbuf, int off, int len) {
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        } catch (IOException e) {
            if (e.getCause() instanceof SAXException) {
                throw (SAXException) e.getCause();
            } else {
                throw new TikaException("Unable to extract PDF content", e);
            }
        }
        if (pdf2XHTML.exceptions.size() > 0) {
            //throw the first
            throw new TikaException("Unable to extract PDF content", pdf2XHTML.exceptions.get(0));
        }
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        try {
            super.processPage(page);
        } catch (IOException e) {
            handleCatchableIOE(e);
            endPage(page);
        }
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        try {
           writeParagraphEnd();
            try {
                extractImages(page);
            } catch (IOException e) {
                handleCatchableIOE(e);
            }
            super.endPage(page);
        } catch (SAXException e) {
            throw new IOException("Unable to end a page", e);
        } catch (IOException e) {
            handleCatchableIOE(e);
        }
    }

    void extractImages(PDPage page) throws SAXException, IOException {
        if (config.getExtractInlineImages() == false) {
            return;
        }

        ImageGraphicsEngine engine = new ImageGraphicsEngine(page, embeddedDocumentExtractor,
                config, processedInlineImages, inlineImageCounter, xhtml, metadata, context);
        engine.run();
        List<IOException> engineExceptions = engine.getExceptions();
        if (engineExceptions.size() > 0) {
            IOException first = engineExceptions.remove(0);
            if (config.getCatchIntermediateIOExceptions()) {
                exceptions.addAll(engineExceptions);
            }
            throw first;
        }
    }
    /*
        @Override
        protected void writeParagraphStart() throws IOException {
            super.writeParagraphStart();
            try {
                System.out.println();
                xhtml.startElement("div", "style", "position:relative;");
            } catch (SAXException e) {
                throw new IOException("Unable to start a paragraph", e);
            }
        }
    */
    @Override
    protected void writeParagraphStart() throws IOException {
        super.writeParagraphStart();
        //System.out.println("paragraph start");
    }
    /*
        @Override
        protected void writeParagraphEnd() throws IOException {
            super.writeParagraphEnd();
            try {
                xhtml.endElement("div");
            } catch (SAXException e) {
                throw new IOException("Unable to end a paragraph", e);
            }
        }

     */
    @Override
    protected void writeParagraphEnd() throws IOException {
        super.writeParagraphEnd();
        //System.out.println("paragraph end");
    }

    private List<List<String>> writeStringInner(String text, List<TextPosition> textPositions) throws IOException {
        ArrayList<String> words = new ArrayList<>();
        StringBuffer wordBuf = new StringBuffer();
        ArrayList<List<Float>> wordStartPos = new ArrayList<>();
        ArrayList<List<Float>> wordEndPos = new ArrayList<>();
        ArrayList<List<String>> wordFonts = new ArrayList<>(20);
        ArrayList<Integer> splitPoints = new ArrayList<>(20);
        ArrayList<Float> indents = new ArrayList<>(20);

        TextPosition prevTextPosition = null;
        //get rid of consecutive spaces
        List<TextPosition> goodPositions = new ArrayList<>();
        //skip leading training and consecutive spaces
        for (int i = 0; i < textPositions.size(); i++) {
            TextPosition s = textPositions.get(i);
            boolean isSpace = s.getUnicode().equals(" ");
            boolean prevCharIsSpace = prevTextPosition != null && prevTextPosition.getUnicode().equals(" ");
            boolean firstLastSpace = isSpace && i == 0 || i == textPositions.size() - 1;
            boolean skip = isSpace && (prevCharIsSpace || firstLastSpace);
            if (!skip) {
                goodPositions.add(s);
            }
            prevTextPosition = s;
        }
        if (goodPositions.size() > 0 && goodPositions.get(goodPositions.size() - 1).getUnicode().equals(" ")){
            goodPositions.remove(goodPositions.size() - 1);
        }
        if (goodPositions.size() == 0) {
            return new ArrayList<>();
        }
        float endX = 0;
        float endY = 0;
        float top = goodPositions.get(0).getYDirAdj();
        float height = goodPositions.get(0).getHeightDir();
        splitPoints.add(0);
        indents.add(goodPositions.get(0).getXDirAdj());
        String fontWeight = "normal";
        String fontStyle = "normal";

        prevTextPosition = null;
        for (int i = 0; i < goodPositions.size(); i++) {
            TextPosition s = goodPositions.get(i);
            PDFontDescriptor fd = s.getFont().getFontDescriptor();

            float fontSizeInPt = s.getFontSizeInPt();
            float fontSize = s.getYScale();
            float fontSpaceWidth = s.getWidthOfSpace();
            float startX = s.getXDirAdj();
            float startY = s.getYDirAdj();
            endX = s.getEndX();
            endY = s.getEndY();
            String fontType = fd.getFontFamily();

            if (fontType == null) {
                fontType = fd.getFontName();
                if (fontType.contains("+")) {
                    fontType = fontType.split("\\+")[1];
                }
                if (fontType.contains(",")) {
                    String[] arr = fontType.split(",");
                    if (arr[1].toLowerCase(Locale.ENGLISH).contains("bold")) {
                        fontWeight = "bold";
                    } else if (arr[1].toLowerCase(Locale.ENGLISH).contains("italic")) {
                        fontStyle = "italic";
                    }
                    fontType = arr[0];
                }
            }
            if (fontType != null) {
                if (fontType.toLowerCase().contains("bold")) {
                    fontWeight = "600";
                } else if (fontType.toLowerCase().contains("italic")) {
                    fontStyle = "italic";
                }
            }

            float fw = fd.getFontWeight();
            if (fontWeight.equals("normal") && fw >= 100) {
                fontWeight = Float.toString(fw);
            }
            if (fd.getItalicAngle() != 0) {
                fontStyle = "italic";
            }

            String currChar = s.getUnicode();
            if (currChar.equals(" ")) {
                words.add(wordBuf.toString());
                //" " considered as part of word
                wordEndPos.add(Arrays.asList(endX, endY));
                wordBuf = new StringBuffer();
            } else {
                if (wordBuf.length() == 0) {//first charactor of word
                    wordStartPos.add(Arrays.asList(startX, startY));
                    wordFonts.add(Arrays.asList(fontType, fontWeight, fontStyle,
                            Float.toString(fontSize), Float.toString(fontSizeInPt), Float.toString(fontSpaceWidth)));
                    if (words.size() > 0) {
                        float gap = startX - prevTextPosition.getEndX();
                        if (gap > 1.0) {
                            splitPoints.add(words.size());
                            indents.add(s.getXDirAdj());
                        }
                    }
                }
                wordBuf.append(currChar);
            }
            prevTextPosition = s;
        }
        //write last word
        words.add(wordBuf.toString());
        wordEndPos.add(Arrays.asList(endX, endY));

        if (wordStartPos.size() != wordEndPos.size()) {
            System.out.println("!!!!!!!Error");
            System.out.println(wordFonts.size() + ", " + wordStartPos.size() + ", " + wordEndPos.size());
            System.out.println(text);
            System.out.println(words);
        }
        List<List<String>> result = new ArrayList<>();
        for (int j = 0; j < splitPoints.size(); j++) {
            int splitStart = splitPoints.get(j);
            int splitEnd = 0;
            if (j == splitPoints.size() - 1) {
                splitEnd = words.size();
            } else {
                splitEnd = splitPoints.get(j + 1);
            }
            String indent = "text-indent:" + indents.get(j) + "px;";
            List<List<Float>> splitStartPos = wordStartPos.subList(splitStart, splitEnd);
            List<List<Float>> splitEndPos = wordEndPos.subList(splitStart, splitEnd);
            List<List<String>> splitFonts = wordFonts.subList(splitStart, splitEnd);
            List<String> firstWordFont = wordFonts.get(0);
            List<String> fontsStr = new ArrayList<>();
            List<String> startStr = new ArrayList<>();
            List<String> endStr = new ArrayList<>();
            for (int k = 0; k < splitFonts.size(); k++) {
                startStr.add("(" +
                        splitStartPos.get(k).get(0) + "," +
                        splitStartPos.get(k).get(1) + ")");
                endStr.add("(" +
                        splitEndPos.get(k).get(0) + "," +
                        splitEndPos.get(k).get(1) + ")");
                fontsStr.add("(" + String.join(",", splitFonts.get(k)) + ")");
            }
            String fontTypeStr = "font-family:" + firstWordFont.get(0) + ";";
            String fontWeightStr = "font-weight:" + firstWordFont.get(1) + ";";
            String fontStyleStr = "font-style:" + firstWordFont.get(2) + ";";
            String fontSizeStr =  "font-size:" + firstWordFont.get(3) + "px;";
            String spanText = String.join(" ", words.subList(splitStart, splitEnd));
            String topStr = "top:" + Float.toString(top) + "px;";
            String val =
                    "height:" + height + ";" +
                            fontSizeStr +
                            fontTypeStr +
                            fontStyleStr +
                            fontWeightStr +
                            topStr + "position:absolute;" +
                            indent +
                            "word-start-positions:" + startStr +
                            ";word-end-positions:" + endStr +
                            ";word-fonts:" + fontsStr;
            result.add(Arrays.asList(spanText, val));
        }
        return result;
    }

//    @Override
//    public boolean getSortByPosition() {
//        return true;
//    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        try {
            List<List<String>> result = this.writeStringInner(text, textPositions);
            for (List<String> row: result) {
                String spanText = row.get(0);
                String styleText = row.get(1);
                xhtml.startElement("p", "style", styleText);
                xhtml.characters(spanText);
                xhtml.endElement("p");
            }


        } catch (SAXException e) {
            throw new IOException(
                    "Unable to write a string: " + text, e);
        }
    }

    @Override
    protected void writeCharacters(TextPosition text) throws IOException {
        try {
            xhtml.characters(text.getUnicode());
        } catch (SAXException e) {
            throw new IOException(
                    "Unable to write a character: " + text.getUnicode(), e);
        }
    }

    @Override
    protected void writeWordSeparator() throws IOException {
        try {
            xhtml.characters(getWordSeparator());
        } catch (SAXException e) {
            throw new IOException(
                    "Unable to write a space character", e);
        }
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        try {
            xhtml.newline();
        } catch (SAXException e) {
            throw new IOException(
                    "Unable to write a newline character", e);
        }
    }

    class AngleCollector extends PDFTextStripper {
        Set<Integer> angles = new HashSet<>();

        public Set<Integer> getAngles() {
            return angles;
        }

        /**
         * Instantiate a new PDFTextStripper object.
         *
         * @throws IOException If there is an error loading the properties.
         */
        AngleCollector() throws IOException {
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            Matrix m = text.getTextMatrix();
            m.concatenate(text.getFont().getFontMatrix());
            int angle = (int) Math.round(Math.toDegrees(Math.atan2(m.getShearY(), m.getScaleY())));
            angle = (angle + 360) % 360;
            angles.add(angle);
        }
    }

    private static class AngleDetectingPDF2XHTML extends PDF2XHTML {

        private AngleDetectingPDF2XHTML(PDDocument document, ContentHandler handler, ParseContext context, Metadata metadata, PDFParserConfig config) throws IOException {
            super(document, handler, context, metadata, config);
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            //no-op
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            //no-op
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            try {
                super.startPage(page);
                detectAnglesAndProcessPage(page);
            } catch (IOException e) {
                handleCatchableIOE(e);
            } finally {
                super.endPage(page);
            }
        }

        private void detectAnglesAndProcessPage(PDPage page) throws IOException {
            //copied and pasted from https://issues.apache.org/jira/secure/attachment/12947452/ExtractAngledText.java
            //PDFBOX-4371
            AngleCollector angleCollector = new AngleCollector(); // alternatively, reset angles
            angleCollector.setStartPage(getCurrentPageNo());
            angleCollector.setEndPage(getCurrentPageNo());
            angleCollector.getText(document);

            int rotation = page.getRotation();
            page.setRotation(0);

            for (Integer angle : angleCollector.getAngles()) {
                if (angle == 0) {
                    try {
                        super.processPage(page);
                    } catch (IOException e) {
                        handleCatchableIOE(e);
                    }
                } else {
                    // prepend a transformation
                    try (PDPageContentStream cs = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.PREPEND, false)) {
                        cs.transform(Matrix.getRotateInstance(-Math.toRadians(angle), 0, 0));
                    }

                    try {
                        super.processPage(page);
                    } catch (IOException e) {
                        handleCatchableIOE(e);
                    }

                    // remove transformation
                    COSArray contents = (COSArray) page.getCOSObject().getItem(COSName.CONTENTS);
                    contents.remove(0);
                }
            }
            page.setRotation(rotation);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            Matrix m = text.getTextMatrix();
            m.concatenate(text.getFont().getFontMatrix());
            int angle = (int) Math.round(Math.toDegrees(Math.atan2(m.getShearY(), m.getScaleY())));
            if (angle == 0) {
                super.processTextPosition(text);
            }
        }
    }
}

