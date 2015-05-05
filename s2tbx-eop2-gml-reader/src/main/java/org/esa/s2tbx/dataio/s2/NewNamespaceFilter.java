/*
 *
 * Copyright (C) 2014-2015 CS SI
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *  This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 *
 */

package org.esa.s2tbx.dataio.s2;


import com.vividsolutions.jts.geom.Polygon;
import org.geotools.gml3.GMLConfiguration;
import org.geotools.xml.StreamingParser;
import org.jdom2.Content;
import org.jdom2.Content.CType;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.XMLOutputter;
import org.jdom2.util.IteratorIterable;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class NewNamespaceFilter {

    private final GMLReader innerReader;
    private final XMLOutputter xmlOutput;

    public NewNamespaceFilter() throws JAXBException {
        this.innerReader = new GMLReader();
        this.xmlOutput = new XMLOutputter();
    }

    /**
     * Parses GML3 without specifying a schema location, and illusrates the use
     * of the streaming parser.
     */
    public static void streamParseGML3(InputStream in) throws Exception {
        GMLConfiguration gml = new GMLConfiguration();
        StreamingParser parser = new StreamingParser( gml, in, Polygon.class );

        int nfeatures = 0;
        Polygon f = null;
        while( ( f = (Polygon) parser.parse() ) != null ) {
            nfeatures++;
            System.out.println(f.getLength());
        }

        System.out.println("Number of features: " + nfeatures);
    }

    public List<JAXBElement> parse(String fileName) throws Exception {
        List<JAXBElement> gmlElementsRecovered = new ArrayList<>();

        InputStream stream = getClass().getResourceAsStream(fileName);

        // Use a SAX builder
        SAXBuilder builder = new SAXBuilder();
        // build a JDOM2 Document using the SAXBuilder.
        Document jdomDoc = builder.build(stream);

        //get the root element
        Element web_app = jdomDoc.getRootElement();

        IteratorIterable<Content> contents = web_app.getDescendants();
        while (contents.hasNext()) {
            Content web_app_content = contents.next();
            if (!web_app_content.getCType().equals(CType.Text) && !web_app_content.getCType().equals(CType.Comment))
            {
                boolean withGml = (web_app_content.getNamespacesInScope().get(0).getPrefix().contains("gml"));
                if(withGml)
                {
                    boolean parentNotGml = !(web_app_content.getParentElement().getNamespace().getPrefix().contains("gml"));
                    if(parentNotGml)
                    {
                        Element capturedElement = (Element) web_app_content;

                        Document newDoc = new Document(capturedElement.clone().detach());

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        xmlOutput.output(newDoc, baos);
                        String content = baos.toString();

                        InputStream ois = new ByteArrayInputStream(baos.toString().replace("/www.opengis.net/gml/3.2","/www.opengis.net/gml").getBytes());

                        streamParseGML3(ois);

                        System.err.println(content);
                    }
                }
            }
        }

        return gmlElementsRecovered;
    }
}