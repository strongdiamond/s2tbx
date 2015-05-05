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

import junit.framework.Assert;
import org.junit.Test;

/**
 * Created by opicas-p on 20/02/2015.
 */
public class NewNamespaceReaderTest {

    public Object getMask(String uri) throws Exception
    {
        NewNamespaceFilter gr = new NewNamespaceFilter();
        gr.parse(uri);
        return gr;
    }

    @Test
    public void testDefect() throws Exception
    {
        Object mat = getMask("l1c/gml-eop/reseop2.xml");

        Assert.assertNotNull(mat);
    }
}
