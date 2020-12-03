package org.apache.tika.extractor;

import com.google.common.io.Files;
import org.apache.commons.lang3.builder.ToStringExclude;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.jpeg.JpegParser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ExpandedTitleContentHandler;
import org.junit.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;


public class TestMe {
    @Test
    public void testSimple() throws IOException {
        //File file = new File("/Users/reshavabraham/work/data/morgan_stanley_data/res23.pdf");
        // File file = new File("/Users/reshavabraham/work/data/test.pdf");
        // File file = new File("/Users/reshavabraham/scp/scp2/125 Greenwich_Deal Intro Update - Financing.pdf");
        // File file = new File("/Users/reshavabraham/scp/scp2/111_Leroy_OM_FINAL OM.pdf");
        // File file = new File("/Users/reshavabraham/scp/original-docs/scp2/11 Jane Street_Final OM_Lo Res_Senior.pdf");

        //File file = new File("/Users/reshavabraham/scp/original-docs/scp2/111 Washington Executive Summary.v.6.1.18.pdf");
        //File file = new File("/Users/reshavabraham/scp/original-docs/scp4/The Godfrey Hotel Phoenix - Oxford Capital Group +True North - Debt OM.pdf");
        //File file = new File("/Users/reshavabraham/scp/original-docs/scp1/190108 BPH DevBudget Cash-Flow Draft.pdf");
        //File file = new File("/Users/reshavabraham/work/pdf_data/nlm-data/original-docs/scp1/The Sunnyside Development.pdf");
        File file = new File("/Users/reshavabraham/work/pdf_data/nlm-data/original-docs/test2/document6.pdf");
        byte[] bytes = Files.toByteArray(file);
        AutoDetectParser tikaParser = new AutoDetectParser();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SAXTransformerFactory factory = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
        TransformerHandler handler;
        try {
            handler = factory.newTransformerHandler();
        } catch (TransformerConfigurationException ex) {
            throw new IOException(ex);
        }
        handler.getTransformer().setOutputProperty(OutputKeys.METHOD, "html");
        handler.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
        handler.getTransformer().setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        handler.setResult(new StreamResult(out));
        ExpandedTitleContentHandler handler1 = new ExpandedTitleContentHandler(handler);
        try {
            tikaParser.parse(new ByteArrayInputStream(bytes), handler1, new Metadata());
        } catch (SAXException | TikaException ex) {
            throw new IOException(ex);
        }
        try {
            FileWriter myWriter = new FileWriter("/Users/reshavabraham/work/nlm-dev/nlm-tika-outputs/dev/test-tika.html");
            myWriter.write(out.toString());
            myWriter.close();
            System.out.println("Successfully wrote to the file.");
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
        System.out.println(out.toString());

    }

    @Test
    public void  testThumbnail() throws IOException, TikaException, SAXException {

        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        FileInputStream inputstream = new FileInputStream(new File("/Users/reshavabraham/work/pdf_data/nlm-data/original-docs/111_Leroy_OM_FINAL.pdf"));

        PDDocument pdfDoc = new PDDocument();
        PDDocument pages = PDDocument.load(inputstream);
        PDPage page = pages.getPage(0);

        PDDocument document = new PDDocument();

        // Create a new blank page and add it to the document
        document.addPage(page);

        // Save the newly created document
        File temp = File.createTempFile("tikafirstpage", ".txt");
        document.save(temp);

        ParseContext pcontext = new ParseContext();

        //parsing the document using PDF parser
        PDFParser pdfparser = new PDFParser();
        FileInputStream t = new FileInputStream(temp);
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(true);
        pdfConfig.setExtractUniqueInlineImagesOnly(true);
        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);


        // PDDocument pdfDoc1 = new PDDocument();
        PDDocument pdfDoc1 = PDDocument.load(temp);
        PDFRenderer pr = new PDFRenderer (pdfDoc1);
        BufferedImage bi = pr.renderImageWithDPI (0, 300);
        ImageIO.write (bi, "JPEG", new File ("/Users/reshavabraham/work/test_out/out123.jpeg"));

        document.close();
        temp.delete();
    }

    @Test
    public void testOcr() throws IOException, TikaException, SAXException {
        FileInputStream inputstream = new FileInputStream(new File("/Users/reshavabraham/work/pdf_data/nlm-data/original-docs/sunnyside.pdf"));
        PDDocument pdfDoc = new PDDocument();
        PDDocument pages = PDDocument.load(inputstream);
        PDPage page = pages.getPage(1);
        // Create a new empty document
        PDDocument document = new PDDocument();

        // Create a new blank page and add it to the document
        document.addPage(page);

        // Save the newly created document
        File temp = File.createTempFile("tikafirstpage", ".txt");
        document.save(temp);

        // finally make sure that the document is properly
        // closed.
        document.close();

        Parser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(Integer.MAX_VALUE);

        TesseractOCRConfig config = new TesseractOCRConfig();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(true);

        PDFParserConfig pdfParserConfig = new PDFParserConfig();
        pdfParserConfig.setOcrStrategy("ocr_only");
        ParseContext parseContext = new ParseContext();
        parseContext.set(TesseractOCRConfig.class, config);
        parseContext.set(PDFParserConfig.class, pdfConfig);
        parseContext.set(PDFParserConfig.class, pdfParserConfig);
        //need to add this to make sure recursive parsing happens!
        parseContext.set(Parser.class, parser);

        FileInputStream stream1 = new FileInputStream(temp);
        Metadata metadata1 = new Metadata();
        parser.parse(stream1, handler, metadata1, parseContext);
        String content = handler.toString();
        System.out.println(content);
        temp.delete();
    }
    @Test
    public void  getTikaDoc() throws IOException {
        //File file = new File("/Users/reshavabraham/work/data/morgan_stanley_data/res23.pdf");
        // File file = new File("/Users/reshavabraham/work/data/test.pdf");
        File file = new File("/Users/reshavabraham/scp/original-docs/scp2/125 Greenwich_Deal Intro Update - Financing.pdf");
        //File file = new File("/Users/reshavabraham/scp/scp2/111_Leroy_OM_FINAL OM.pdf");
        //File file = new File(pathname);
        byte[] bytes = Files.toByteArray(file);
        AutoDetectParser tikaParser = new AutoDetectParser();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SAXTransformerFactory factory = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
        TransformerHandler handler;
        try {
            handler = factory.newTransformerHandler();
        } catch (TransformerConfigurationException ex) {
            throw new IOException(ex);
        }
        handler.getTransformer().setOutputProperty(OutputKeys.METHOD, "html");
        handler.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
        handler.getTransformer().setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        handler.setResult(new StreamResult(out));
        ExpandedTitleContentHandler handler1 = new ExpandedTitleContentHandler(handler);
        try {
            tikaParser.parse(new ByteArrayInputStream(bytes), handler1, new Metadata());
        } catch (SAXException | TikaException ex) {
            throw new IOException(ex);
        }
        try {
            FileWriter myWriter = new FileWriter("/Users/reshavabraham/work/nlm-tika/out.html");
            myWriter.write(out.toString());
            myWriter.close();
            System.out.println("Successfully wrote to the file.");
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
        System.out.println(out.toString());

    }

    @Test
    public void indexDocs() throws IOException {
        File folder = new File("/Users/reshavabraham/scp/original-docs/scp4/");
        File[] listOfFiles = folder.listFiles();
        String currFile = "";
        for (File listOfFile : listOfFiles) {
            if (listOfFile.isFile() && !listOfFile.getName().equals(".DS_Store")) {
                currFile = "/Users/reshavabraham/scp/original-docs/scp4/" + listOfFile.getName();
                File file = new File(currFile);
                byte[] bytes = Files.toByteArray(file);
                AutoDetectParser tikaParser = new AutoDetectParser();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                SAXTransformerFactory factory = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
                TransformerHandler handler;
                try {
                    handler = factory.newTransformerHandler();
                } catch (TransformerConfigurationException ex) {
                    throw new IOException(ex);
                }
                handler.getTransformer().setOutputProperty(OutputKeys.METHOD, "html");
                handler.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
                handler.getTransformer().setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                handler.setResult(new StreamResult(out));
                ExpandedTitleContentHandler handler1 = new ExpandedTitleContentHandler(handler);
                try {
                    tikaParser.parse(new ByteArrayInputStream(bytes), handler1, new Metadata());
                } catch (SAXException | TikaException ex) {
                    throw new IOException(ex);
                }
                try {
                    FileWriter myWriter = new FileWriter("/Users/reshavabraham/scp/tika-modded-067/scp4/" + listOfFile.getName() + ".html");
                    myWriter.write(out.toString());
                    myWriter.close();
                    System.out.println("Successfully wrote to the file.");
                } catch (IOException e) {
                    System.out.println("An error occurred.");
                    e.printStackTrace();
                }
                System.out.println(out.toString());

            } else if (listOfFile.isDirectory()) {
                System.out.println("Directory " + listOfFile.getName());
            }
        }
    }
}
