package org.apache.tika.extractor;

import com.google.common.io.Files;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.ExpandedTitleContentHandler;
import org.junit.Test;
import org.xml.sax.SAXException;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.*;

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
        File file = new File("/Users/reshavabraham/work/pdf_data/nlm-data/original-docs/equities/CHP_Clean.pdf");
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
