/*
 * Copyright (c) 2008, SQL Power Group Inc.
 *
 * This file is part of Power*Architect.
 *
 * Power*Architect is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Power*Architect is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.architect.swingui.olap;

import org.apache.log4j.Logger;

import ca.sqlpower.architect.olap.MondrianModel.Dimension;
import ca.sqlpower.architect.olap.MondrianModel.Hierarchy;
import ca.sqlpower.architect.swingui.PlayPenComponent;

public class BasicDimensionPaneUI extends OLAPPaneUI<Dimension, Hierarchy> {
    
    @SuppressWarnings("unused")
    private static Logger logger = Logger.getLogger(BasicDimensionPaneUI.class);

    public static BasicDimensionPaneUI createUI(DimensionPane dp) {
        return new BasicDimensionPaneUI();
    }
    
    @Override
    public void installUI(PlayPenComponent c) {
        super.installUI(c);
        paneSections.add(new PaneSectionImpl<Hierarchy>(olapPane.getModel().getHierarchies(), null));
    }
    
    @Override
    public void uninstallUI(PlayPenComponent c) {
        super.uninstallUI(c);
        paneSections.clear();
    }

}
