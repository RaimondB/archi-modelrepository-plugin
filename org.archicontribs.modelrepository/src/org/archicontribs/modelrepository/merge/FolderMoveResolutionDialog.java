/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.merge;

import java.util.List;

import org.archicontribs.modelrepository.grafico.FolderMoveInfo;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.archimatetool.editor.ui.IArchiImages;
import com.archimatetool.editor.ui.components.ExtendedTitleAreaDialog;

/**
 * Dialog to let the user resolve folder moves detected during merge repair.
 * Shows each moved folder with its old/new location, duplicate/unique elements,
 * and lets the user choose which location to keep.
 * 
 * @author Raimond Brookman
 */
public class FolderMoveResolutionDialog extends ExtendedTitleAreaDialog {

    private static String DIALOG_ID = "FolderMoveResolutionDialog"; //$NON-NLS-1$

    private List<FolderMoveInfo> fMoves;
    private TableViewer fTableViewer;
    private Text fOldLocationText;
    private Text fNewLocationText;
    private Text fDuplicatesText;
    private Text fUniqueText;
    private Button fKeepNewButton;
    private Button fKeepOldButton;
    private FolderMoveInfo fCurrentSelection;

    public FolderMoveResolutionDialog(Shell parentShell, List<FolderMoveInfo> moves) {
        super(parentShell, DIALOG_ID);
        fMoves = moves;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.FolderMoveResolutionDialog_0);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setMessage(Messages.FolderMoveResolutionDialog_1, IMessageProvider.WARNING);
        setTitleImage(IArchiImages.ImageFactory.getImage(IArchiImages.ECLIPSE_IMAGE_IMPORT_PREF_WIZARD));
        setTitle(Messages.FolderMoveResolutionDialog_0);

        Composite area = (Composite)super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(GridData.FILL_BOTH));
        container.setLayout(new GridLayout());

        SashForm sash = new SashForm(container, SWT.VERTICAL);
        sash.setLayoutData(new GridData(GridData.FILL_BOTH));

        createTableControl(sash);
        createDetailsPanel(sash);

        sash.setWeights(new int[] { 35, 65 });

        // Select first item
        Object first = fTableViewer.getElementAt(0);
        if(first != null) {
            fTableViewer.setSelection(new StructuredSelection(first));
        }

        return area;
    }

    private void createTableControl(Composite parent) {
        Composite tableComposite = new Composite(parent, SWT.NONE);
        TableColumnLayout tableLayout = new TableColumnLayout();
        tableComposite.setLayout(tableLayout);

        fTableViewer = new TableViewer(tableComposite, SWT.FULL_SELECTION | SWT.BORDER | SWT.SINGLE);
        fTableViewer.getTable().setHeaderVisible(true);
        fTableViewer.getTable().setLinesVisible(true);

        // Column: Folder Name
        TableViewerColumn colName = new TableViewerColumn(fTableViewer, SWT.NONE);
        colName.getColumn().setText(Messages.FolderMoveResolutionDialog_2);
        tableLayout.setColumnData(colName.getColumn(), new ColumnWeightData(25, true));

        // Column: Old Location
        TableViewerColumn colOld = new TableViewerColumn(fTableViewer, SWT.NONE);
        colOld.getColumn().setText(Messages.FolderMoveResolutionDialog_3);
        tableLayout.setColumnData(colOld.getColumn(), new ColumnWeightData(30, true));

        // Column: New Location
        TableViewerColumn colNew = new TableViewerColumn(fTableViewer, SWT.NONE);
        colNew.getColumn().setText(Messages.FolderMoveResolutionDialog_4);
        tableLayout.setColumnData(colNew.getColumn(), new ColumnWeightData(30, true));

        // Column: Choice
        TableViewerColumn colChoice = new TableViewerColumn(fTableViewer, SWT.NONE);
        colChoice.getColumn().setText(Messages.FolderMoveResolutionDialog_5);
        tableLayout.setColumnData(colChoice.getColumn(), new ColumnWeightData(15, true));

        fTableViewer.setContentProvider(new IStructuredContentProvider() {
            @Override
            public Object[] getElements(Object inputElement) {
                return fMoves.toArray();
            }
        });

        fTableViewer.setLabelProvider(new MoveLabelProvider());

        fTableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                Object sel = fTableViewer.getStructuredSelection().getFirstElement();
                if(sel instanceof FolderMoveInfo info) {
                    fCurrentSelection = info;
                    updateDetails(info);
                    updateButtons(info);
                }
            }
        });

        fTableViewer.setInput(fMoves);
    }

    private void createDetailsPanel(Composite parent) {
        Composite detailsComposite = new Composite(parent, SWT.NONE);
        detailsComposite.setLayout(new GridLayout(2, false));

        // Old location
        createLabel(detailsComposite, Messages.FolderMoveResolutionDialog_3 + ":"); //$NON-NLS-1$
        fOldLocationText = createReadOnlyText(detailsComposite);

        // New location
        createLabel(detailsComposite, Messages.FolderMoveResolutionDialog_4 + ":"); //$NON-NLS-1$
        fNewLocationText = createReadOnlyText(detailsComposite);

        // Duplicate elements group
        Group dupGroup = new Group(detailsComposite, SWT.NONE);
        dupGroup.setText(Messages.FolderMoveResolutionDialog_6);
        dupGroup.setLayout(new GridLayout());
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.horizontalSpan = 2;
        dupGroup.setLayoutData(gd);

        fDuplicatesText = new Text(dupGroup, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
        fDuplicatesText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Unique elements group
        Group uniqueGroup = new Group(detailsComposite, SWT.NONE);
        uniqueGroup.setText(Messages.FolderMoveResolutionDialog_7);
        uniqueGroup.setLayout(new GridLayout());
        gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.horizontalSpan = 2;
        uniqueGroup.setLayoutData(gd);

        fUniqueText = new Text(uniqueGroup, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
        fUniqueText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Choice buttons
        Composite buttonComposite = new Composite(detailsComposite, SWT.NONE);
        gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        buttonComposite.setLayoutData(gd);
        buttonComposite.setLayout(new GridLayout(2, true));

        fKeepNewButton = new Button(buttonComposite, SWT.PUSH);
        fKeepNewButton.setText(Messages.FolderMoveResolutionDialog_8);
        fKeepNewButton.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        fKeepNewButton.addListener(SWT.Selection, e -> {
            if(fCurrentSelection != null) {
                fCurrentSelection.setUserChoice(FolderMoveInfo.KEEP_NEW_LOCATION);
                fTableViewer.update(fCurrentSelection, null);
                updateButtons(fCurrentSelection);
            }
        });

        fKeepOldButton = new Button(buttonComposite, SWT.PUSH);
        fKeepOldButton.setText(Messages.FolderMoveResolutionDialog_9);
        fKeepOldButton.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        fKeepOldButton.addListener(SWT.Selection, e -> {
            if(fCurrentSelection != null) {
                fCurrentSelection.setUserChoice(FolderMoveInfo.KEEP_OLD_LOCATION);
                fTableViewer.update(fCurrentSelection, null);
                updateButtons(fCurrentSelection);
            }
        });
    }

    private void updateDetails(FolderMoveInfo info) {
        fOldLocationText.setText(info.getOldRelativePath());
        fNewLocationText.setText(info.getNewRelativePath());

        StringBuilder dups = new StringBuilder();
        if(info.getDuplicateElementFiles() != null) {
            for(String name : info.getDuplicateElementFiles()) {
                if(dups.length() > 0) dups.append('\n');
                dups.append(formatElementFileName(name));
            }
        }
        fDuplicatesText.setText(dups.length() > 0 ? dups.toString()
                : Messages.FolderMoveResolutionDialog_10);

        StringBuilder uniq = new StringBuilder();
        if(info.getUniqueElementFiles() != null) {
            for(String name : info.getUniqueElementFiles()) {
                if(uniq.length() > 0) uniq.append('\n');
                uniq.append(formatElementFileName(name));
            }
        }
        fUniqueText.setText(uniq.length() > 0 ? uniq.toString()
                : Messages.FolderMoveResolutionDialog_10);
    }

    private void updateButtons(FolderMoveInfo info) {
        boolean isNew = info.getUserChoice() == FolderMoveInfo.KEEP_NEW_LOCATION;
        String selSuffix = " " + Messages.FolderMoveResolutionDialog_11; //$NON-NLS-1$
        fKeepNewButton.setText(Messages.FolderMoveResolutionDialog_8
                + (isNew ? selSuffix : "")); //$NON-NLS-1$
        fKeepOldButton.setText(Messages.FolderMoveResolutionDialog_9
                + (!isNew ? selSuffix : "")); //$NON-NLS-1$
    }

    /**
     * Format an element filename for display, e.g. "BusinessActor_id-abc.xml" → "BusinessActor (id-abc)"
     */
    private String formatElementFileName(String fileName) {
        if(fileName == null) return ""; //$NON-NLS-1$
        // Remove .xml extension
        String name = fileName.endsWith(".xml") //$NON-NLS-1$
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        // Split on first underscore: Type_id
        int underscore = name.indexOf('_');
        if(underscore > 0) {
            return name.substring(0, underscore) + " (" + name.substring(underscore + 1) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return name;
    }

    private Label createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return label;
    }

    private Text createReadOnlyText(Composite parent) {
        Text text = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return text;
    }

    @Override
    protected Point getDefaultDialogSize() {
        return new Point(750, 600);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
    }

    // Label provider for the moves table
    private class MoveLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            if(element instanceof FolderMoveInfo info) {
                return switch(columnIndex) {
                    case 0 -> info.getFolderName();
                    case 1 -> info.getOldRelativePath();
                    case 2 -> info.getNewRelativePath();
                    case 3 -> info.getUserChoice() == FolderMoveInfo.KEEP_NEW_LOCATION
                            ? Messages.FolderMoveResolutionDialog_8
                            : Messages.FolderMoveResolutionDialog_9;
                    default -> ""; //$NON-NLS-1$
                };
            }
            return ""; //$NON-NLS-1$
        }
    }
}
