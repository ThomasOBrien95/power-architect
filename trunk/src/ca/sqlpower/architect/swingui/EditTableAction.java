package ca.sqlpower.architect.swingui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.*;
import ca.sqlpower.architect.*;
import org.apache.log4j.Logger;

public class EditTableAction extends AbstractAction {
	private static final Logger logger = Logger.getLogger(EditTableAction.class);

	/**
	 * The PlayPen instance that owns this Action.
	 */
	protected PlayPen pp;
	
	public EditTableAction() {
		super("Table Properties...",
			  ASUtils.createIcon("TableProperties",
								 "Table Properties",
								 ArchitectFrame.getMainInstance().sprefs.getInt(SwingUserSettings.ICON_SIZE, 24)));
		putValue(SHORT_DESCRIPTION, "Table Properties");
	}

	public void actionPerformed(ActionEvent evt) {
		Selectable invoker = pp.getSelection();
		if (invoker instanceof TablePane) {
			TablePane tp = (TablePane) invoker;
			
			JTabbedPane tabbedPane = new JTabbedPane();

			final JDialog d = new JDialog(ArchitectFrame.getMainInstance(),
										  "Table Properties");
										  
			// first tabbed Pane							  
			JPanel cp = new JPanel(new BorderLayout(12,12));
			cp.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
			final TableEditPanel editPanel = new TableEditPanel(tp.getModel());
			cp.add(editPanel, BorderLayout.CENTER);
			
			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			
			JButton okButton = new JButton("Ok");
			okButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent evt) {
						editPanel.applyChanges();
						d.setVisible(false);
					}
				});
			buttonPanel.add(okButton);
			
			JButton cancelButton = new JButton("Cancel");
			cancelButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent evt) {
						editPanel.discardChanges();
						d.setVisible(false);
					}
				});
			buttonPanel.add(cancelButton);
			
			cp.add(buttonPanel, BorderLayout.SOUTH);
			tabbedPane.addTab("TableProperties",cp);
			
			// second tabbed Pane
			JPanel mcp = new JPanel(new BorderLayout(12,12));
			mcp.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
			//
			JTable mapTable = new JTable();
			JScrollPane scrollpMap = new JScrollPane(mapTable);
            mcp.add(scrollpMap,BorderLayout.NORTH);
			JPanel buttonMapPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
			
			JButton okMapButton = new JButton("Ok");
			okMapButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent evt) {
						//editPanel.applyChanges();
						//d.setVisible(false);
					}
				});
			buttonMapPanel.add(okMapButton);
			
			JButton cancelMapButton = new JButton("Cancel");
			cancelMapButton.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent evt) {
						//editPanel.discardChanges();
						//d.setVisible(false);
					}
				});
			buttonMapPanel.add(cancelMapButton);
			mcp.add(buttonMapPanel, BorderLayout.SOUTH);
			tabbedPane.addTab("Column Mappings",mcp);
			
			
			
			
			d.setContentPane(tabbedPane);
			d.pack();
			d.setVisible(true);
			
		} else {
			JOptionPane.showMessageDialog((JComponent) invoker,
										  "The selected item type is not recognised");
		}
	}

	public void setPlayPen(PlayPen pp) {
		this.pp = pp;
	}
}
