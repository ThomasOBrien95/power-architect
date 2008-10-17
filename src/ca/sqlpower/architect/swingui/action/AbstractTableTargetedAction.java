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
package ca.sqlpower.architect.swingui.action;

import java.awt.event.ActionEvent;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.tree.TreePath;

import ca.sqlpower.architect.ArchitectException;
import ca.sqlpower.architect.SQLObject;
import ca.sqlpower.architect.swingui.ASUtils;
import ca.sqlpower.architect.swingui.ArchitectSwingConstants;
import ca.sqlpower.architect.swingui.ArchitectSwingSession;
import ca.sqlpower.architect.swingui.Selectable;
import ca.sqlpower.architect.swingui.TablePane;
import ca.sqlpower.architect.swingui.dbtree.DBTree;
import ca.sqlpower.architect.swingui.event.SelectionEvent;
import ca.sqlpower.architect.swingui.event.SelectionListener;

public abstract class AbstractTableTargetedAction extends AbstractArchitectAction implements SelectionListener {

    /**
     * The DBTree instance that is associated with this Action.
     */
    protected final DBTree dbt; 
    
    public AbstractTableTargetedAction(
            ArchitectSwingSession session,
            String actionName,
            String actionDescription,
            String iconResourceName) {
        super(session, actionName, actionDescription, iconResourceName);
        
        dbt = frame.getDbTree();
        if (dbt == null) throw new NullPointerException("Null db tree"); //$NON-NLS-1$
        
        playpen.addSelectionListener(this);
        
        setupAction(playpen.getSelectedItems());
    }
    
    public void actionPerformed(ActionEvent evt) {
        try {
            if (evt.getActionCommand().equals(ArchitectSwingConstants.ACTION_COMMAND_SRC_PLAYPEN)) {
                List selection = playpen.getSelectedItems();
                if (selection.size() < 1) {
                    JOptionPane.showMessageDialog(playpen, Messages.getString("AbstractTableTargetedAction.selectTable")); //$NON-NLS-1$
                } else if (selection.size() > 1) {
                    JOptionPane.showMessageDialog(playpen, Messages.getString("AbstractTableTargetedAction.multipleItemsSelected")); //$NON-NLS-1$
                } else if (selection.get(0) instanceof TablePane) {
                    TablePane tp = (TablePane) selection.get(0);
                    processTablePane(tp);
                } else {
                    JOptionPane.showMessageDialog(playpen, Messages.getString("AbstractTableTargetedAction.selectedItemNotRecognized")); //$NON-NLS-1$
                }
            } else if (evt.getActionCommand().equals(ArchitectSwingConstants.ACTION_COMMAND_SRC_DBTREE)) {
                TreePath [] selections = dbt.getSelectionPaths();
                if (selections == null || selections.length != 1) {
                    JOptionPane.showMessageDialog(dbt, Messages.getString("AbstractTableTargetedAction.instructions")); //$NON-NLS-1$
                } else {
                    TreePath tp = selections[0];
                    SQLObject so = (SQLObject) tp.getLastPathComponent();
                    processSQLObject(so);
                }
            } else {
                JOptionPane.showMessageDialog(
                        null, "InsertColumnAction: Unknown Action Command \"" +  //$NON-NLS-1$
                        evt.getActionCommand() + "\"", //$NON-NLS-1$
                        "Internal Architect Error", JOptionPane.ERROR_MESSAGE); //$NON-NLS-1$
            }
        } catch (ArchitectException ex) {
            ASUtils.showExceptionDialog(session, Messages.getString("AbstractTableTargetedAction.columnCouldNotBeInserted") + ex.getMessage(), ex); //$NON-NLS-1$
        }
    }
    

    abstract void processTablePane(TablePane tp) throws ArchitectException;
    abstract void processSQLObject(SQLObject so) throws ArchitectException;

    
    public void setupAction(List selectedItems) {
        if (selectedItems.size() == 0) {
            disableAction();
        } else {
            Selectable item = (Selectable) selectedItems.get(0);
            if (item instanceof TablePane)              
                setEnabled(true);
        }
    }
    
    public abstract void disableAction();
        
    public void itemSelected(SelectionEvent e) {
        setupAction(playpen.getSelectedItems());
        
    }

    public void itemDeselected(SelectionEvent e) {
        setupAction(playpen.getSelectedItems());
    }
}