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

import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;

import ca.sqlpower.architect.ArchitectException;
import ca.sqlpower.architect.ArchitectRuntimeException;
import ca.sqlpower.architect.SQLColumn;
import ca.sqlpower.architect.SQLTable;
import ca.sqlpower.architect.olap.OLAPUtil;
import ca.sqlpower.architect.olap.MondrianModel.Hierarchy;
import ca.sqlpower.architect.olap.MondrianModel.Table;
import ca.sqlpower.validation.Validator;
import ca.sqlpower.validation.swingui.FormValidationHandler;
import ca.sqlpower.validation.swingui.StatusComponent;
import ca.sqlpower.validation.swingui.ValidatableDataEntryPanel;
import ca.sqlpower.validation.swingui.ValidationHandler;

import com.jgoodies.forms.builder.DefaultFormBuilder;
import com.jgoodies.forms.layout.FormLayout;

public class HierarchyEditPanel implements ValidatableDataEntryPanel {
    
    private final Hierarchy hierarchy;
    private final JPanel panel;
    private final JTextField name;
    private final JTextField captionField;
    private final JComboBox tableChooser;
    private final JCheckBox hasAll;
    private final JTextField allLevelName;
    private final JComboBox primaryKey;
    
    /**
     * Validation handler for errors in the dialog
     */
    private FormValidationHandler handler;
    private StatusComponent status = new StatusComponent();
    
    
    /**
     * Creates a new property editor for the given OLAP Hierarchy. 
     * 
     * @param cube The data model of the hierarchy to edit
     */
    public HierarchyEditPanel(Hierarchy hierarchy) throws ArchitectException {
        this.hierarchy = hierarchy;
        
        FormLayout layout = new FormLayout(
                "left:max(40dlu;pref), 3dlu, 80dlu:grow", "");
        DefaultFormBuilder builder = new DefaultFormBuilder(layout);
        builder.setDefaultDialogBorder();
        builder.append(status, 3);
        builder.append("Name", name = new JTextField(hierarchy.getName()));
        builder.append("Caption", captionField = new JTextField(hierarchy.getCaption()));
        builder.append("Has All", hasAll = new JCheckBox());
        hasAll.setSelected(hierarchy.getHasAll() != null ? hierarchy.getHasAll() : true);
        builder.append("All Level Name", allLevelName = new JTextField(hierarchy.getAllLevelName() != null ? hierarchy.getAllLevelName() : "(All)"));

        builder.append("Table", tableChooser = new JComboBox());
        builder.append("Primary Key", primaryKey = new JComboBox());
        
        List<SQLTable> tables = OLAPUtil.getAvailableTables(hierarchy);
        if (tables.isEmpty()) {
            tableChooser.addItem("Database has no tables");
            tableChooser.setEnabled(false);
            primaryKey.addItem("Table not selected");
            primaryKey.setEnabled(false);
        } else {
            for (SQLTable tab : tables) {
                tableChooser.addItem(tab);
            }
            
            SQLTable t = OLAPUtil.tableForHierarchy(hierarchy);
            if (t != null && tables.contains(t)) {
                tableChooser.setSelectedItem(t);
            } else {
                t = (SQLTable) tableChooser.getSelectedItem();
            }
            tableChooser.setEnabled(true);

            updateColumns(hierarchy.getPrimaryKey());
        }
        
        tableChooser.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                updateColumns(null);
            }
        });
        
        handler = new FormValidationHandler(status);
        Validator validator = new OLAPObjectNameValidator(hierarchy.getParent(), hierarchy, true);
        handler.addValidateObject(name, validator);
        
        panel = builder.getPanel();
    }
    
    /**
     * Updates the column chooser combo box according to the table selected.
     * 
     * @param primaryKeyName Name of the column to set selected.
     */
    private void updateColumns(String primaryKeyName) {
        primaryKey.removeAllItems();
        SQLTable selectedTable = (SQLTable) tableChooser.getSelectedItem();
        boolean enableColumns = false;
        try {
            if (selectedTable.getColumns().isEmpty()) {
                primaryKey.addItem("Table has no columns");
            } else {
                for (SQLColumn col : selectedTable.getColumns()) {
                    primaryKey.addItem(col);
                    if (col.getName().equalsIgnoreCase(primaryKeyName)) {
                        primaryKey.setSelectedItem(col);
                    }
                }
                enableColumns = true;
            }
            primaryKey.setEnabled(enableColumns);
        } catch (ArchitectException ex) {
            throw new ArchitectRuntimeException(ex);
        }
    }
    
    public boolean applyChanges() {
        hierarchy.startCompoundEdit("Modify hierarchy properties");
        if (!(name.getText().equals(""))) {
            hierarchy.setName(name.getText());
        } else {
            hierarchy.setName(null);
        }
        if (!(captionField.getText().equals(""))) {
            hierarchy.setCaption(captionField.getText());
        } else {
            hierarchy.setCaption(null);
        }
        hierarchy.setHasAll(hasAll.isSelected());
        hierarchy.setAllLevelName(allLevelName.getText());
        if (tableChooser.isEnabled()) {
            SQLTable t = (SQLTable) tableChooser.getSelectedItem();
            Table table = new Table();
            table.setName(t.getName());
            table.setSchema(OLAPUtil.getQualifier(t));
            hierarchy.setRelation(table);
            
            if (primaryKey.isEnabled()) {
                SQLColumn column = (SQLColumn) primaryKey.getSelectedItem();
                hierarchy.setPrimaryKey(column.getName());
            }
        }

        hierarchy.endCompoundEdit();
        return true;
    }

    public void discardChanges() {
        // nothing to do
    }

    public JComponent getPanel() {
        return panel;
    }

    public boolean hasUnsavedChanges() {
        return true;
    }
    public ValidationHandler getValidationHandler() {
        return handler;
    }

}