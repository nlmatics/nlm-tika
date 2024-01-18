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
 * 
 * ------------------------------------------------------------
 * New graphics processor contributed by Ambika Sukla, Nlmatics Corp.
 * ------------------------------------------------------------
 * Detect lines and rectangles information to help detect tables
 */

package org.apache.tika.parser.pdf;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.SAXException;
import org.apache.tika.sax.XHTMLContentHandler;
import java.lang.String;

import java.awt.geom.*;
import java.io.IOException;

public class GraphicsStreamProcessor extends PDFGraphicsStreamEngine
{
    private Point2D.Float currentPoint = new Point2D.Float(0, 0);
    private Point2D.Float startingPoint = new Point2D.Float(0, 0);
    private int edgeCount = 0;

    private float[] fillComponents = null;
    private float[] strokeComponents = null;
    private int clipWindingRule = -1;
    private StringBuffer htmlBuf;
    private XHTMLContentHandler xhtml;
    private float offsetY = (float) 0.0;
    public GraphicsStreamProcessor(PDPage page, float offsetY, StringBuffer htmlBuf, XHTMLContentHandler xhtml)
    {
        super(page);
        this.htmlBuf = htmlBuf;
        this.xhtml = xhtml;
//        this.offsetY = offsetY;
    }

    private String getRGB(float[] components, float[] defaultComponents) {
        float[] rgbComponents = defaultComponents;
        if(components.length == 3) {
            rgbComponents = components;
        } else {
//            System.out.println("bad stroke colors" + components);
        }
        return "rgb(" + rgbComponents[0]*255 + "," + rgbComponents[1]*255 + "," + rgbComponents[2]*255 + ")";
    }
    private String getStrokeStyle() {
        String strokeColor = getRGB(getGraphicsState().getStrokingColor().getComponents(), new float[]{0, 0, 0});
        String fillColor = "none";
        float strokeWidth = this.getGraphicsState().getLineWidth();
        if (fillComponents != null) {
            fillColor = getRGB(fillComponents, new float[]{255, 255, 255});
            strokeColor = getRGB(fillComponents, new float[]{0, 0, 0});
        } else if (strokeComponents != null) {
            strokeColor = getRGB(strokeComponents, new float[]{0, 0, 0});
        }
        String styleStr = "fill:" + fillColor + ";" + "stroke-width:" + strokeWidth + ";stroke:" + strokeColor;
        return styleStr;
    }
    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);
    }
    //    @Override
    public  void appendRectangleX(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {

    }
    private String createSVGRect(double x, double y, double width, double height) throws IOException {
        String styleStr = this.getStrokeStyle();
        String rectStr = "<rect x='" + x + "' y='" + y + "' width='" + width + "' height='" + height + "'" + "style='" + styleStr + "'/>";

        if (xhtml != null) {
            try {
                AttributesImpl attrs = new AttributesImpl();
                attrs.addAttribute("", "x", "x", "CDATA", String.valueOf(x));
                attrs.addAttribute("", "y", "y", "CDATA", String.valueOf(y));
                attrs.addAttribute("", "width", "width", "CDATA", String.valueOf(width));
                attrs.addAttribute("", "height", "height", "CDATA", String.valueOf(height));
                attrs.addAttribute("", "style", "style", "CDATA", styleStr);
                xhtml.startElement("rect", attrs);
                xhtml.characters("");
                xhtml.endElement("rect");
            } catch (SAXException e) {
                throw new IOException("Unable to end a page", e);
            }
        }

        return rectStr;
    }
    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException
    {
        PDPage page = getCurrentPage();


        double x = p3.getX();
        double y = page.getMediaBox().getHeight() - p3.getY() + offsetY;
        double width = Math.abs(p1.getX() - p0.getX());
        double height = Math.abs(p1.getY() - p3.getY());
        if (this.getGraphicsState().getLineWidth() > 0) {
            String rectStr = createSVGRect(x, y, width, height);
//            System.out.println(rectStr);
            htmlBuf.append(rectStr);
            htmlBuf.append("\n");
        }
    }

    @Override
    public void drawImage(PDImage pdi) throws IOException
    {
        System.out.println("should draw image...");
    }

    @Override
    public void clip(int windingRule) throws IOException
    {
        // the clipping path will not be updated until the succeeding painting operator is called
        clipWindingRule = windingRule;

    }

    @Override
    public void moveTo(float x, float y) throws IOException
    {
        currentPoint.setLocation(x, y);

    }

    private Point2D transformPoint(Point2D point) {
        PDPage page = this.getCurrentPage();
        PDRectangle mediaBox = page.getMediaBox();
        PDRectangle cropBox = page.getCropBox();
        PDRectangle viewBox = (cropBox != null ? cropBox : mediaBox);
        Matrix txm = getGraphicsState().getCurrentTransformationMatrix();
        AffineTransform at = txm.createAffineTransform();
        Point2D txPoint = new Point2D.Float(0, 0);
        at.transform(point, txPoint);
        double x1 = point.getX();
        double y1 = viewBox.getHeight() - point.getY() + offsetY;
        return new Point2D.Double(x1, y1);
    }

    private String createSVGLine(Point2D point1, Point2D point2) throws IOException {
        String styleStr = this.getStrokeStyle();
        Point2D txmPoint1 = transformPoint(point1);
        Point2D txmPoint2 = transformPoint(point2);

        String lineStr = "<line x1='" + txmPoint1.getX()
                + "' y1='" + txmPoint1.getY()
                + "' x2='" + txmPoint2.getX() + "' y2='" + txmPoint2.getY() + "' " + "style='" + styleStr + "'/>";

        if (xhtml != null) {
            try {
                AttributesImpl attrs = new AttributesImpl();
                attrs.addAttribute("", "x1", "x1", "CDATA", String.valueOf(txmPoint1.getX()));
                attrs.addAttribute("", "y1", "y1", "CDATA", String.valueOf(txmPoint1.getY()));
                attrs.addAttribute("", "x2", "x2", "CDATA", String.valueOf(txmPoint2.getX()));
                attrs.addAttribute("", "y2", "y2", "CDATA", String.valueOf(txmPoint2.getY()));
                attrs.addAttribute("", "style", "style", "CDATA", styleStr);
                xhtml.startElement("line", attrs);
                xhtml.characters("");
                xhtml.endElement("line");
            } catch (SAXException e) {
                throw new IOException("Unable to process", e);
            }
        }

        return lineStr;
    }

    public void lineTo(float x, float y) throws IOException
    {

        if (this.getGraphicsState().getLineWidth() > 0) {
            String svgLine = createSVGLine(currentPoint, new Point2D.Float(x, y));


            if (edgeCount == 0) {
                startingPoint.setLocation(currentPoint.getX(), currentPoint.getY());
            }

            currentPoint.setLocation(x, y);

            htmlBuf.append(svgLine);
            htmlBuf.append("\n");
            edgeCount += 1;
        }
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException
    {
    }

    @Override
    public Point2D getCurrentPoint() throws IOException
    {
        return currentPoint;
    }

    @Override
    public void closePath() throws IOException
    {
        this.startingPoint.setLocation(0, 0);
        this.fillComponents = null;
        this.strokeComponents = null;
        this.edgeCount = 0;
    }

    @Override
    public void endPath() throws IOException
    {
        this.fillComponents = null;
        this.strokeComponents = null;
        if (this.edgeCount == 3) {
            String svgLine = createSVGLine(currentPoint, startingPoint);
            htmlBuf.append(svgLine);
            htmlBuf.append("\n");
        }
        this.startingPoint.setLocation(0, 0);
        this.edgeCount = 0;
    }

    @Override
    public void strokePath() throws IOException
    {
        this.fillComponents = null;
        this.strokeComponents = this.getGraphicsState().getNonStrokingColor().getComponents();
    }

    @Override
    public void fillPath(int windingRule) throws IOException
    {
        this.strokeComponents = null;
        this.fillComponents = this.getGraphicsState().getNonStrokingColor().getComponents();
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException
    {
        this.fillComponents = this.getGraphicsState().getNonStrokingColor().getComponents();
        this.strokeComponents = this.getGraphicsState().getNonStrokingColor().getComponents();
    }

    @Override
    public void shadingFill(COSName cosn) throws IOException
    {
        System.out.println("shading fill..." + cosn);
    }
}