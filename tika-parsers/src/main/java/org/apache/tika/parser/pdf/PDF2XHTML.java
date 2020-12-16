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

    // @Override
    // protected void writeString(String text) throws IOException {
    //     try {
    //         //text = text + "tika-hack";
    //         // text = text + "<embed charX>";
    //         xhtml.characters(text);
    //     } catch (SAXException e) {
    //         throw new IOException(
    //                 "Unable to write a string: " + text, e);
    //     }
    // }

//    TextPosition textPosition = textPositions.get(0);
//    PDFont font = textPosition.getFont();
//    PDFontDescriptor fontDescriptor = font.getFontDescriptor();
//    // this is font size in points?
//    float fontSize = textPosition.getFontSize();
//    float xScale = font.getFontMatrix().getScaleX();
//    float yScale = font.getFontMatrix().getScaleY();
//    float spaceWidth = font.getSpaceWidth()*xScale*fontSize;
//    float fontSizePx = fontDescriptor.getCapHeight()*yScale*fontSize;
//    float fontHeight = font.getBoundingBox().getHeight()*yScale*fontSize;


    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        try {
            StringBuilder testWordStartPos = new StringBuilder();

            ArrayList<String> wordsStartPos = new ArrayList<>(20);
            ArrayList<String> wordsEndPos = new ArrayList<>(20);
            ArrayList<List<String>> wordsFonts = new ArrayList<>(20);
            ArrayList<String> wordSpaceDistanceList = new ArrayList<>(20);
            ArrayList<Float> wordGaps = new ArrayList<>(20);
            ArrayList<Float> fontSpaceWidths = new ArrayList<>(20);
            ArrayList<List<Integer>> splitPoints = new ArrayList<>(20);
            ArrayList<Float> indents = new ArrayList<>(20);
            // int wordCount = text.split("\\s+").length;
            // String[] wordsStartPos2 = new String[wordCount];

            float linePositiony = 0;
            float linePositionx = 0;
            float endY = 0;
            float endX = 0;
            float fontXScale = 0;
            float fontYScale = 0;
            String last_char_pos = "[]";
            String height = "";
            String y_rel = "";
            String font_type = "";
            String font_weight = "normal";
            String font_style = "normal";
            String word_start_pos = "";
            //xhtml.startElement("div", "style", "border:3px solid ##ff0000;");
//        String s1 = Float.toString(textPositions.get(0).getXDirAdj() * 1);
//        String indent = "text-indent:" + s1 + "px;";
            splitPoints.add(Arrays.asList(0, 0));
            indents.add(textPositions.get(0).getXDirAdj());
            String prev = " ";
//            String height1 = "start-font-size:" + Float.toString((float) Math.pow(textPositions.get(0).getHeightDir(), 1)) + "px;";
//            String top1 = "top1:" + Float.toString(textPositions.get(0).getYDirAdj()) + "px;";
            String font_size = "";
            String font_size_pt = "";
            String font_size_px = "";
            String font_space_width = "";

            float prevWordEndX = 0;
            //for (TextPosition s : textPositions) {
            // each text position is a character??
            for (int i = 0; i < textPositions.size(); i++) {

                TextPosition s = textPositions.get(i);
                PDFontDescriptor fd = s.getFont().getFontDescriptor();
                fontXScale = s.getFont().getFontMatrix().getScaleX();
                fontYScale = s.getFont().getFontMatrix().getScaleY();
                height = Float.toString((float) Math.pow(s.getHeightDir(), 1));
                font_size_pt = Float.toString(s.getFontSizeInPt());
//                font_size = Float.toString(fd.getCapHeight()*fontYScale*s.getFontSizeInPt());
                font_size = Float.toString(s.getYScale());
                font_space_width = Float.toString(s.getWidthOfSpace());
                y_rel = "top:" + Float.toString(s.getYDirAdj()) + "px;";
                linePositiony = s.getYDirAdj();
                linePositionx = s.getXDirAdj();
                endY = s.getEndY();
                endX = s.getEndX();

                font_type = fd.getFontFamily();
                if (font_type == null) {
                    font_type = fd.getFontName();

                    if (font_type.contains("+")) {
                        font_type = font_type.split("\\+")[1];
                    }

                    if (font_type.contains(",")) {
                        String[] arr = font_type.split(",");
                        if (arr[1].toLowerCase(Locale.ENGLISH).contains("bold")) {
                            font_weight = "bold";
                        }
                        font_type = arr[0];
                    }
                }

                float fw = fd.getFontWeight();
                if (font_weight.equals("normal") && fw >= 100) {
                    font_weight = Float.toString(fw);
                }
                if (fd.getItalicAngle() != 0) {
                    font_style = "italic";
                }

                if (i + 1 < textPositions.size()) {
                    if (textPositions.get(i + 1).toString().equals(" ")) {
                        // if next char is a space save get the position of the last char of the word
                        wordsEndPos.add("(" + endX +
                                "," + endY
                                + ")");
                        prevWordEndX = endX;
                    }
                } else {
                    // last char
                    wordsEndPos.add("(" + endX +
                            "," + endY +
                            ")");
                }

                // get start of word in format (xCoord, yCoord)
                if (prev.equals(" ")) {
                    //String tempWordPos = "(" + linePositionx + "," + linePositiony + ")";
                    String tempWordPos = "(" + linePositionx + "," + linePositiony + ")";
                    testWordStartPos.append("(").append(linePositionx).append(",").append(linePositiony).append(")").append("current char: ").append(s.toString());
                    wordsStartPos.add(tempWordPos.toString());
                    wordsFonts.add(Arrays.asList(font_type, font_weight, font_style, font_size, font_size_pt, font_space_width));
                    if (wordsFonts.size() > 1) {
                        float gap = linePositionx - prevWordEndX;
                        float fontSpaceWidth = s.getWidthOfSpace();//s.getFont().getSpaceWidth()*fontXScale*s.getFontSizeInPt();
                        wordGaps.add(gap);
                        fontSpaceWidths.add(fontSpaceWidth);
                        if (gap > 2*fontSpaceWidth) {
                            splitPoints.add(Arrays.asList(i, wordsFonts.size() - 1));
                            indents.add(s.getXDirAdj());
                        }
                    }
                }
                prev = s.toString();
                last_char_pos = "(" + Float.toString(linePositionx) + ", " + Float.toString(linePositiony) + ")";
            }
            //text = This is a test
            height = "height:" + height + "px;";
//        if (splitPoints.size() > 1) {
//            System.out.println("**********" + text);
//        }

            for (int j = 0; j < splitPoints.size(); j++) {
                int splitWordStart = splitPoints.get(j).get(1);
                int splitCharStart = splitPoints.get(j).get(0);
                int splitWordEnd = 0;
                int splitCharEnd = 0;
                int endOfEnd = 0; //wow!!!!!!!
                if (j == splitPoints.size() - 1) {
                    splitWordEnd = splitPoints.size();
                    splitCharEnd = textPositions.size();
                    endOfEnd = wordsEndPos.size();
                } else {
                    splitWordEnd = splitPoints.get(j + 1).get(1);
                    splitCharEnd = splitPoints.get(j + 1).get(0);
                    endOfEnd = splitWordEnd;
                }
//            System.out.println(splitWordStart + ", " + splitWordEnd + ", " + splitCharStart + ", " + splitCharEnd);
//            System.out.println(text + "->" + text.length() + "->" + splitPoints.size() + splitStart + ", " + splitEnd);
                String indent = "text-indent:" + indents.get(j) + "px;";
                word_start_pos = "word-start-positions:" + wordsStartPos.subList(splitWordStart, splitWordEnd).toString();
                String word_end_pos = ";word-end-positions:" + wordsEndPos.subList(splitWordStart, endOfEnd).toString();
                List<List<String>> word_fonts = wordsFonts.subList(splitWordStart, splitWordEnd);
                List<String> firstWordFont = word_fonts.get(0);
                List<String> allWordFonts = new ArrayList<>();
                for (int k = 0; k < word_fonts.size(); k++) {
                    String fontStr = "(" + String.join(",", word_fonts.get(k)) + ")";
                    allWordFonts.add(fontStr);
                }
//                wordsFonts.add(Arrays.asList(font_type, font_weight, font_style, font_size, font_size_pt, font_space_width));
                font_type = "font-family:" + firstWordFont.get(0) + ";";
                font_weight = "font-weight:" + firstWordFont.get(1) + ";";
                font_style = "font-style:" + firstWordFont.get(2) + ";";
                font_size_px = "font-size:" + firstWordFont.get(3) + "px;";
//                font_size_pt = "font-size:" + firstWordFont.get(3) + "px;";
                String spanText = text.substring(splitCharStart, splitCharEnd);
                String val =
                        height +
                                font_size_px +
//                            font_size_pt +
                                font_type +
                                font_style +
                                font_weight +
                                y_rel + "position:absolute;" +
                                indent +
                                word_start_pos +
                                ";last-char:" + last_char_pos +
                                word_end_pos +
                                ";word-fonts:" + allWordFonts;

                xhtml.startElement("p", "style", val);
                xhtml.characters(spanText);
                xhtml.endElement("p");
                //            if (splitPoints.size() > 1) {
//                System.out.println(">>>> " + spanText);
//                System.out.println(word_start_pos);
//                System.out.println(word_end_pos);
//                System.out.println(word_fonts);
//                System.out.println("gaps:" + wordGaps);
//                System.out.println("spaceWidths:" + fontSpaceWidths);
//                System.out.println("\n");
//            }
            }
//        System.out.println(val);
            //String val = height + y_rel  + indent;
            //xhtml.endElement("div");
        } catch (SAXException e) {
            throw new IOException(
                    "Unable to write a string: " + text, e);
        }
    }

    private String getWordFontString(String font_type, String font_weight, String font_style, String font_size, String font_size_pt, String font_space_width) {
        return "(" + font_type +
                "," + font_style +
                "," + font_size +
                "," + font_weight +
                "," + font_size_pt +
                "," + font_space_width +
                ")";
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

