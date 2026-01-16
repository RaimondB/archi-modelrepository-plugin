/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.review;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ComboBoxCellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;

import com.archimatetool.editor.diagram.util.DiagramUtils;
import com.archimatetool.editor.ui.ArchiLabelProvider;
import com.archimatetool.editor.ui.IArchiImages;
import com.archimatetool.editor.ui.components.ExtendedTitleAreaDialog;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.INameable;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.viewpoints.ViewpointManager;

/**
 * Review Changes Dialog
 * 
 * Shows changes between HEAD and current model, allowing user to
 * selectively revert changes.
 * 
 * Based on ConflictsDialog but adapted for change review.
 * 
 * @author Raimond Brookman
 */
public class ReviewChangesDialog extends ExtendedTitleAreaDialog {
    
    private static String DIALOG_ID = "ReviewChangesDialog"; //$NON-NLS-1$

    // Tab Composite base class
    private abstract class TabComposite extends Composite {
        protected int version;
        
        TabComposite(Composite parent, int version) {
            super(parent, SWT.NONE);
            
            this.version = version;
            setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
            setBackgroundMode(SWT.INHERIT_FORCE);
            setLayout(new GridLayout());
        }
        
        abstract void setChangeInfo(ChangeInfo changeInfo);
    }
    
    private ChangeReviewHandler fHandler;
    
    private ChangeInfo currentSelectedChangeInfo;
    
    private TreeViewer fTreeViewer;
    
    private Button revertButton;
    
    private TabFolder tabFolder;
    private TabItem itemView;
    
    private List<TabComposite> fTabComposites = new ArrayList<>();
    
    // Choices for ChangeInfo (elements, relationships, diagrams)
    private String[] choices = {
            Messages.ReviewChangesDialog_25,  // Keep
            Messages.ReviewChangesDialog_26   // Revert
    };
    
    // Choices for DiagramDependencyInfo (missing concepts in diagrams)
    private String[] dependencyChoices = {
            Messages.DiagramDependencyInfo_0,  // Restore element/relationship
            Messages.DiagramDependencyInfo_2   // Remove from diagram
    };
    
    /**
     * Constructor
     * 
     * @param parentShell The parent shell
     * @param handler The change review handler
     */
    ReviewChangesDialog(Shell parentShell, ChangeReviewHandler handler) {
        super(parentShell, DIALOG_ID);
        fHandler = handler;
    }
    
    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.ReviewChangesDialog_2);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setMessage(Messages.ReviewChangesDialog_3, IMessageProvider.INFORMATION);
        setTitleImage(IArchiImages.ImageFactory.getImage(IArchiImages.ECLIPSE_IMAGE_IMPORT_PREF_WIZARD));
        setTitle(Messages.ReviewChangesDialog_2);

        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(GridData.FILL_BOTH));
        container.setLayout(new GridLayout());
        
        SashForm sash = new SashForm(container, SWT.VERTICAL);
        sash.setLayoutData(new GridData(GridData.FILL_BOTH));
        
        createTreeControl(sash);
        createTabPane(sash);
        
        sash.setWeights(new int[] { 25, 75 });
        
        // Select first object in tree
        Object first = fTreeViewer.getTree().getItemCount() > 0 ? 
                       fTreeViewer.getTree().getItem(0).getData() : null;
        if(first != null) {
            fTreeViewer.setSelection(new StructuredSelection(first));
        }
        
        return area;
    }
    
    /**
     * Create the tab pane with comparison views
     */
    private void createTabPane(Composite parent) {
        Composite mainComposite = new Composite(parent, SWT.BORDER);
        mainComposite.setLayoutData(new GridData(GridData.FILL_BOTH));
        mainComposite.setLayout(new GridLayout(2, true));
        
        // Labels for columns
        Label currentLabel = new Label(mainComposite, SWT.CENTER);
        currentLabel.setText(Messages.ReviewChangesDialog_0);  // "Current"
        currentLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        
        Label headLabel = new Label(mainComposite, SWT.CENTER);
        headLabel.setText(Messages.ReviewChangesDialog_1);  // "Previous (HEAD)"
        headLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        
        tabFolder = new TabFolder(mainComposite, SWT.NONE);
        tabFolder.setLayoutData(new GridData(GridData.FILL_BOTH));
        ((GridData)tabFolder.getLayoutData()).horizontalSpan = 2;
        
        createMainTabItem();
        createPropertiesTabItem();
        // View TabItem is created on demand for diagrams
        
        // Revert button - TEMPORARILY DISABLED until revert logic is complete
        revertButton = new Button(mainComposite, SWT.PUSH);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.horizontalSpan = 2;
        gd.exclude = true;  // Hide the button entirely for now
        revertButton.setLayoutData(gd);
        revertButton.setText(Messages.ReviewChangesDialog_20);  // "Revert Selected"
        revertButton.setVisible(false);  // TODO: Show when revert logic is complete
        
        revertButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                fTreeViewer.getTree().setRedraw(false);
                try {
                    List<Object> allChangedElements = new ArrayList<>();
                    
                    for(Object o : fTreeViewer.getStructuredSelection().toArray()) {
                        if(o instanceof ChangeInfo info) {
                            // Toggle: if KEEP, set to REVERT; if REVERT, set to KEEP
                            int newChoice = info.getUserChoice() == ChangeInfo.KEEP ? 
                                    ChangeInfo.REVERT : ChangeInfo.KEEP;
                            
                            // Handle deleted concepts specially - need to cascade to diagrams
                            if(info.getChangeType() == ChangeInfo.DELETED && !info.isDiagram()) {
                                if(newChoice == ChangeInfo.KEEP) {
                                    // Keeping deletion = concept stays deleted
                                    info.setUserChoice(newChoice);
                                    allChangedElements.add(info);
                                    allChangedElements.addAll(cascadeConceptDeletionToDiagrams(info));
                                } else {
                                    // Reverting deletion = concept is restored
                                    info.setUserChoice(newChoice);
                                    allChangedElements.add(info);
                                    allChangedElements.addAll(cascadeConceptRestoreToDiagrams(info));
                                }
                            } else {
                                info.setUserChoice(newChoice);
                                allChangedElements.add(info);
                            }
                            
                            // If reverting a diagram, analyze dependencies
                            if(info.getUserChoice() == ChangeInfo.REVERT && info.isDiagram() && !info.areDependenciesAnalyzed()) {
                                List<DiagramDependencyInfo> deps = fHandler.analyzeDiagramDependencies(info);
                                info.setDependencies(deps);
                            }
                            
                        } else if(o instanceof DiagramDependencyInfo depInfo) {
                            // Toggle dependency choice
                            int newChoice = depInfo.getUserChoice() == DiagramDependencyInfo.RESTORE_CONCEPT ?
                                    DiagramDependencyInfo.REMOVE_FROM_DIAGRAM : DiagramDependencyInfo.RESTORE_CONCEPT;
                            depInfo.setUserChoice(newChoice);
                            allChangedElements.add(depInfo);
                            
                            if(newChoice == DiagramDependencyInfo.RESTORE_CONCEPT) {
                                // Restoring - also restore in model and restore relationship endpoints
                                allChangedElements.addAll(markConceptChangeInfoAsRevertWithTracking(depInfo));
                                if(!depInfo.isElement()) {
                                    allChangedElements.addAll(cascadeRestoreRelationshipEndpointsWithTracking(depInfo));
                                }
                            } else {
                                // Removing - cascade to relationships if it's an element
                                if(depInfo.isElement()) {
                                    allChangedElements.addAll(cascadeRemoveRelationshipsForElementInternal(depInfo));
                                }
                            }
                        }
                    }
                    
                    // Refresh entire tree to show any cascading changes
                    fTreeViewer.refresh();
                    refreshChangedElements(allChangedElements);
                    
                    // Update button text
                    Object selected = fTreeViewer.getStructuredSelection().getFirstElement();
                    if(selected instanceof ChangeInfo info) {
                        updateRevertButton(info);
                    } else if(selected instanceof DiagramDependencyInfo) {
                        updateRevertButtonForDependency((DiagramDependencyInfo)selected);
                    }
                } finally {
                    fTreeViewer.getTree().setRedraw(true);
                }
            }
        });
    }
    
    /**
     * When a diagram dependency is set to RESTORE_CONCEPT, find and mark
     * the corresponding ChangeInfo for that element/relationship as REVERT.
     */
    private void markConceptChangeInfoAsRevert(DiagramDependencyInfo depInfo) {
        markConceptChangeInfoAsRevertWithTracking(depInfo);
    }
    
    /**
     * When a diagram dependency is set to RESTORE_CONCEPT, find and mark
     * the corresponding ChangeInfo for that element/relationship as REVERT.
     * @return List of changed elements for UI update
     */
    private List<Object> markConceptChangeInfoAsRevertWithTracking(DiagramDependencyInfo depInfo) {
        List<Object> changedElements = new ArrayList<>();
        String conceptId = depInfo.getConceptId();
        if(conceptId == null) {
            return changedElements;
        }
        
        // Find the ChangeInfo for this concept
        for(ChangeInfo info : fHandler.getChangeInfos()) {
            String infoId = info.getElementId();
            if(conceptId.equals(infoId)) {
                // Mark as REVERT if currently KEEP
                if(info.getUserChoice() == ChangeInfo.KEEP) {
                    info.setUserChoice(ChangeInfo.REVERT);
                    changedElements.add(info);
                    
                    // Also update any other diagram dependencies for this concept
                    changedElements.addAll(syncOtherDiagramDependenciesWithTracking(conceptId, DiagramDependencyInfo.RESTORE_CONCEPT));
                }
                break;
            }
        }
        return changedElements;
    }
    
    /**
     * Synchronize all diagram dependencies for a given concept to the same choice.
     * This ensures consistency when a concept is marked for restore/remove.
     */
    private void syncOtherDiagramDependencies(String conceptId, int choice) {
        syncOtherDiagramDependenciesWithTracking(conceptId, choice);
    }
    
    /**
     * Synchronize all diagram dependencies for a given concept to the same choice.
     * @return List of changed elements for UI update
     */
    private List<Object> syncOtherDiagramDependenciesWithTracking(String conceptId, int choice) {
        List<Object> changedElements = new ArrayList<>();
        for(ChangeInfo info : fHandler.getChangeInfos()) {
            if(info.isDiagram() && info.areDependenciesAnalyzed()) {
                for(DiagramDependencyInfo dep : info.getDependencies()) {
                    if(conceptId.equals(dep.getConceptId())) {
                        if(dep.getUserChoice() != choice) {
                            dep.setUserChoice(choice);
                            changedElements.add(dep);
                        }
                    }
                }
            }
        }
        return changedElements;
    }
    
    /**
     * When an element is set to REMOVE_FROM_DIAGRAM, also set any relationships
     * that connect to this element to REMOVE_FROM_DIAGRAM.
     * 
     * Relationships that have their source or target removed cannot exist in the diagram.
     */
    private void cascadeRemoveRelationshipsForElement(DiagramDependencyInfo elementDep) {
        cascadeRemoveRelationshipsForElementInternal(elementDep);
    }
    
    /**
     * When a relationship is restored in a diagram, also restore its source and target elements.
     * A relationship cannot exist without both endpoints.
     */
    private void cascadeRestoreRelationshipEndpoints(DiagramDependencyInfo relDep) {
        cascadeRestoreRelationshipEndpointsWithTracking(relDep);
    }
    
    /**
     * When a relationship is restored in a diagram, also restore its source and target elements.
     * @return List of changed elements for UI update
     */
    private List<Object> cascadeRestoreRelationshipEndpointsWithTracking(DiagramDependencyInfo relDep) {
        List<Object> changedElements = new ArrayList<>();
        
        if(relDep.isElement() || !(relDep.getHeadConcept() instanceof IArchimateRelationship rel)) {
            return changedElements;
        }
        
        ChangeInfo parentDiagram = relDep.getParent();
        if(parentDiagram == null || !parentDiagram.hasDependencies()) {
            return changedElements;
        }
        
        String sourceId = rel.getSource() != null ? rel.getSource().getId() : null;
        String targetId = rel.getTarget() != null ? rel.getTarget().getId() : null;
        
        // Find and restore the source and target elements in this diagram
        for(DiagramDependencyInfo dep : parentDiagram.getDependencies()) {
            if(dep.isElement()) {
                String depId = dep.getConceptId();
                if(depId.equals(sourceId) || depId.equals(targetId)) {
                    if(dep.getUserChoice() == DiagramDependencyInfo.REMOVE_FROM_DIAGRAM) {
                        dep.setUserChoice(DiagramDependencyInfo.RESTORE_CONCEPT);
                        changedElements.add(dep);
                        // Also restore in model
                        changedElements.addAll(markConceptChangeInfoAsRevertWithTracking(dep));
                    }
                }
            }
        }
        return changedElements;
    }
    
    /**
     * When a model-level concept is set to KEEP (stays deleted), cascade to remove from all diagrams.
     * A concept cannot appear in a diagram if it doesn't exist in the model.
     * 
     * @return List of elements that were changed (for UI update)
     */
    private List<Object> cascadeConceptDeletionToDiagrams(ChangeInfo conceptInfo) {
        List<Object> changedElements = new ArrayList<>();
        String conceptId = conceptInfo.getElementId();
        if(conceptId == null) {
            return changedElements;
        }
        
        // Find all diagram dependencies referencing this concept and set to REMOVE_FROM_DIAGRAM
        for(ChangeInfo info : fHandler.getChangeInfos()) {
            if(info.isDiagram() && info.areDependenciesAnalyzed()) {
                for(DiagramDependencyInfo dep : info.getDependencies()) {
                    if(conceptId.equals(dep.getConceptId())) {
                        dep.setUserChoice(DiagramDependencyInfo.REMOVE_FROM_DIAGRAM);
                        changedElements.add(dep);
                        
                        // If it's an element, also cascade to relationships
                        if(dep.isElement()) {
                            changedElements.addAll(cascadeRemoveRelationshipsForElementInternal(dep));
                        }
                    }
                }
            }
        }
        return changedElements;
    }
    
    /**
     * When a model-level concept is set to REVERT (restored), cascade to restore in all diagrams.
     * Default behavior is to restore, but user can still choose to remove from specific diagrams.
     * 
     * @return List of elements that were changed (for UI update)
     */
    private List<Object> cascadeConceptRestoreToDiagrams(ChangeInfo conceptInfo) {
        List<Object> changedElements = new ArrayList<>();
        String conceptId = conceptInfo.getElementId();
        if(conceptId == null) {
            return changedElements;
        }
        
        // Find all diagram dependencies referencing this concept and set to RESTORE_CONCEPT
        for(ChangeInfo info : fHandler.getChangeInfos()) {
            if(info.isDiagram() && info.areDependenciesAnalyzed()) {
                for(DiagramDependencyInfo dep : info.getDependencies()) {
                    if(conceptId.equals(dep.getConceptId())) {
                        dep.setUserChoice(DiagramDependencyInfo.RESTORE_CONCEPT);
                        changedElements.add(dep);
                    }
                }
            }
        }
        return changedElements;
    }
    
    /**
     * Internal cascade for removing relationships (returns changed elements for tracking).
     */
    private List<Object> cascadeRemoveRelationshipsForElementInternal(DiagramDependencyInfo elementDep) {
        List<Object> changedElements = new ArrayList<>();
        String elementId = elementDep.getConceptId();
        ChangeInfo parentDiagram = elementDep.getParent();
        
        if(elementId == null || parentDiagram == null || !parentDiagram.hasDependencies()) {
            return changedElements;
        }
        
        // Find relationships in this diagram that reference the removed element
        for(DiagramDependencyInfo dep : parentDiagram.getDependencies()) {
            if(!dep.isElement() && dep.getHeadConcept() instanceof IArchimateRelationship rel) {
                // Check if this relationship connects to the removed element
                String sourceId = rel.getSource() != null ? rel.getSource().getId() : null;
                String targetId = rel.getTarget() != null ? rel.getTarget().getId() : null;
                
                if(elementId.equals(sourceId) || elementId.equals(targetId)) {
                    // This relationship connects to the removed element - remove it too
                    if(dep.getUserChoice() != DiagramDependencyInfo.REMOVE_FROM_DIAGRAM) {
                        dep.setUserChoice(DiagramDependencyInfo.REMOVE_FROM_DIAGRAM);
                        changedElements.add(dep);
                    }
                }
            }
        }
        return changedElements;
    }
    
    /**
     * Refresh all changed elements in the tree viewer.
     * This ensures the UI immediately reflects cascading changes.
     */
    private void refreshChangedElements(List<Object> changedElements) {
        if(changedElements.isEmpty()) {
            return;
        }
        
        // Update each changed element specifically
        for(Object element : changedElements) {
            fTreeViewer.update(element, null);
        }
    }
    
    // =====================================
    // Main Composite
    // =====================================
    
    private TabItem createMainTabItem() {
        SashForm sash = new SashForm(tabFolder, SWT.HORIZONTAL);
        sash.setBackground(sash.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        
        fTabComposites.add(new MainComposite(sash, ChangeInfo.CURRENT));
        fTabComposites.add(new MainComposite(sash, ChangeInfo.HEAD));
        
        TabItem item = new TabItem(tabFolder, SWT.NONE);
        item.setText(Messages.ReviewChangesDialog_28);  // "Main"
        item.setControl(sash);
        
        return item;
    }
    
    private class MainComposite extends TabComposite {
        private Composite fieldsComposite;
        private Label labelDocumentation;
        private Text textName, textDocumentation;
        
        private Text textSource, textTarget;
        private Text textViewpoint;
        private Text textAccessType, textInfluenceStrength;
        
        MainComposite(Composite parent, int version) {
            super(parent, version);
            
            fieldsComposite = new Composite(this, SWT.NONE);
            fieldsComposite.setLayoutData(new GridData(GridData.FILL_BOTH));
            fieldsComposite.setLayout(new GridLayout(2, false));

            // Name
            createLabel(fieldsComposite, Messages.ReviewChangesDialog_4, null);
            textName = createSingleText(fieldsComposite, null);
            
            // Relationship Source
            createLabel(fieldsComposite, Messages.ReviewChangesDialog_5, IArchimateRelationship.class);
            textSource = createSingleText(fieldsComposite, IArchimateRelationship.class);
            
            // Relationship Target
            createLabel(fieldsComposite, Messages.ReviewChangesDialog_6, IArchimateRelationship.class);
            textTarget = createSingleText(fieldsComposite, IArchimateRelationship.class);
            
            // Access Relationship Type
            createLabel(fieldsComposite, Messages.ReviewChangesDialog_7, IAccessRelationship.class);
            textAccessType = createSingleText(fieldsComposite, IAccessRelationship.class);

            // Influence Relationship Strength
            createLabel(fieldsComposite, Messages.ReviewChangesDialog_8, IInfluenceRelationship.class);
            textInfluenceStrength = createSingleText(fieldsComposite, IInfluenceRelationship.class);

            // Viewpoint
            createLabel(fieldsComposite, Messages.ReviewChangesDialog_9, IArchimateDiagramModel.class);
            textViewpoint = createSingleText(fieldsComposite, IArchimateDiagramModel.class);

            // Documentation / Purpose
            labelDocumentation = new Label(fieldsComposite, SWT.NONE);
            labelDocumentation.setLayoutData(new GridData(SWT.TOP, SWT.TOP, false, false));
            textDocumentation = new Text(fieldsComposite, SWT.READ_ONLY | SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
            textDocumentation.setLayoutData(new GridData(GridData.FILL_BOTH));
        }
        
        @Override
        void setChangeInfo(ChangeInfo changeInfo) {
            EObject eObject = changeInfo.getEObject(version);
            
            // If object doesn't exist for this version, hide fields
            fieldsComposite.setVisible(eObject != null);
            
            if(eObject == null) {
                return;
            }
            
            // Show/Hide controls depending on object class
            for(Control control : fieldsComposite.getChildren()) {
                if(control.getData() instanceof Class) {
                    boolean isVisible = ((Class<?>)control.getData()).isInstance(eObject);
                    control.setVisible(isVisible);
                    ((GridData)control.getLayoutData()).exclude = !isVisible;
                }
            }
            
            // Name
            if(eObject instanceof INameable) {
                textName.setText(((INameable)eObject).getName());
            }
            else {
                textName.setText(""); //$NON-NLS-1$
            }

            // Relationship controls
            if(eObject instanceof IArchimateRelationship) {
                textSource.setText(((IArchimateRelationship)eObject).getSource().getName());
                textTarget.setText(((IArchimateRelationship)eObject).getTarget().getName());
                
                if(eObject instanceof IAccessRelationship) {
                    int type = ((IAccessRelationship)eObject).getAccessType();
                    if(type < IAccessRelationship.WRITE_ACCESS || type > IAccessRelationship.READ_WRITE_ACCESS) {
                        type = IAccessRelationship.WRITE_ACCESS;
                    }
                    final String[] types = { 
                        Messages.ReviewChangesDialog_10, 
                        Messages.ReviewChangesDialog_11, 
                        Messages.ReviewChangesDialog_12, 
                        Messages.ReviewChangesDialog_13 
                    };
                    textAccessType.setText(types[type]);
                }
                else if(eObject instanceof IInfluenceRelationship) {
                    String strength = ((IInfluenceRelationship)eObject).getStrength();
                    textInfluenceStrength.setText(strength);
                }
            }

            // Documentation / Purpose
            if(eObject instanceof IDocumentable) {
                labelDocumentation.setText(Messages.ReviewChangesDialog_14);
                textDocumentation.setText(((IDocumentable)eObject).getDocumentation());
            }
            else if(eObject instanceof IArchimateModel) {
                labelDocumentation.setText(Messages.ReviewChangesDialog_15);
                textDocumentation.setText(((IArchimateModel)eObject).getPurpose());
            }
            else {
                textDocumentation.setText(""); //$NON-NLS-1$
            }
            
            // Viewpoint
            if(eObject instanceof IArchimateDiagramModel) {
                String name = ViewpointManager.INSTANCE.getViewpoint(((IArchimateDiagramModel)eObject).getViewpoint()).getName();
                textViewpoint.setText(name);
            }
            
            fieldsComposite.layout();
        }
    }
    
    private Label createLabel(Composite parent, String text, Class<?> c) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setLayoutData(new GridData());
        label.setData(c);
        return label;
    }
    
    private Text createSingleText(Composite parent, Class<?> c) {
        Text text = new Text(parent, SWT.READ_ONLY | SWT.BORDER);
        text.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        text.setData(c);
        return text;
    }

    // =====================================
    // Properties Composite
    // =====================================
    
    private TabItem createPropertiesTabItem() {
        SashForm sash = new SashForm(tabFolder, SWT.HORIZONTAL);
        sash.setBackground(sash.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        
        fTabComposites.add(new PropertiesComposite(sash, ChangeInfo.CURRENT));
        fTabComposites.add(new PropertiesComposite(sash, ChangeInfo.HEAD));
        
        TabItem item = new TabItem(tabFolder, SWT.NONE);
        item.setText(Messages.ReviewChangesDialog_16);
        item.setControl(sash);
        
        return item;
    }
    
    private class PropertiesComposite extends TabComposite {
        private TableViewer propertiesTableViewer;

        PropertiesComposite(Composite parent, int version) {
            super(parent, version);
            
            Composite tableComp = new Composite(this, SWT.BORDER);
            TableColumnLayout tableLayout = new TableColumnLayout();
            tableComp.setLayout(tableLayout);
            tableComp.setLayoutData(new GridData(GridData.FILL_BOTH));

            propertiesTableViewer = new TableViewer(tableComp, SWT.MULTI | SWT.FULL_SELECTION);
            propertiesTableViewer.getTable().setHeaderVisible(true);
            propertiesTableViewer.getTable().setLinesVisible(true);
            propertiesTableViewer.setComparator(new ViewerComparator(Collator.getInstance()));
            
            TableViewerColumn columnKey = new TableViewerColumn(propertiesTableViewer, SWT.NONE, 0);
            columnKey.getColumn().setText(Messages.ReviewChangesDialog_17);
            tableLayout.setColumnData(columnKey.getColumn(), new ColumnWeightData(25, true));

            TableViewerColumn columnValue = new TableViewerColumn(propertiesTableViewer, SWT.NONE, 1);
            columnValue.getColumn().setText(Messages.ReviewChangesDialog_18);
            tableLayout.setColumnData(columnValue.getColumn(), new ColumnWeightData(75, true));

            // Properties Table Content Provider
            propertiesTableViewer.setContentProvider(new IStructuredContentProvider() {
                @Override
                public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
                }

                @Override
                public void dispose() {
                }

                @Override
                public Object[] getElements(Object inputElement) {
                    if(inputElement instanceof IProperties) {
                        return ((IProperties)inputElement).getProperties().toArray();
                    }
                    return new Object[0];
                }
            });

            // Properties Table Label Provider
            class PropertiesLabelCellProvider extends LabelProvider implements ITableLabelProvider {
                @Override
                public Image getColumnImage(Object element, int columnIndex) {
                    return null;
                }

                @Override
                public String getColumnText(Object element, int columnIndex) {
                    switch(columnIndex) {
                        case 0:
                            return ((IProperty)element).getKey();

                        case 1:
                            return ((IProperty)element).getValue();

                        default:
                            return null;
                    }
                }
            }

            propertiesTableViewer.setLabelProvider(new PropertiesLabelCellProvider());
        }

        @Override
        void setChangeInfo(ChangeInfo changeInfo) {
            EObject eObject = changeInfo.getEObject(version);
            
            // If object doesn't exist, hide table
            propertiesTableViewer.getControl().setVisible(eObject != null);
            
            // Properties
            if(eObject instanceof IProperties) {
                propertiesTableViewer.setInput(eObject);
            }
            else {
                propertiesTableViewer.setInput(""); //$NON-NLS-1$
            }
        }
    }
    
    // =====================================
    // View Composite (for diagrams)
    // =====================================
    
    private TabItem createViewTabItem() {
        SashForm sash = new SashForm(tabFolder, SWT.HORIZONTAL);
        sash.setBackground(sash.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        
        ViewComposite c1 = new ViewComposite(sash, ChangeInfo.CURRENT);
        fTabComposites.add(c1);
        ViewComposite c2 = new ViewComposite(sash, ChangeInfo.HEAD);
        fTabComposites.add(c2);
        
        TabItem item = new TabItem(tabFolder, SWT.NONE);
        item.setText(Messages.ReviewChangesDialog_19);
        item.setControl(sash);
        
        // Dispose listener for images
        item.addDisposeListener((event) -> {
            fTabComposites.remove(c1);
            fTabComposites.remove(c2);
            c1.disposeImages();
            c2.disposeImages();
        });
        
        return item;
    }
    
    private class ViewComposite extends TabComposite {
        private Label viewLabel;
        private Scale scale;
        
        private IDiagramModel diagramModel;
        
        private Map<Integer, Image> scaledImages;
        
        private final int SCALES = 6;
        
        ViewComposite(Composite parent, int version) {
            super(parent, version);
            
            scale = new Scale(this, SWT.HORIZONTAL);
            scale.setMinimum(1);
            scale.setMaximum(SCALES);
            scale.setSelection(SCALES);
            
            scale.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    setScaledImage(scale.getSelection());
                }
            });

            ScrolledComposite scImage = new ScrolledComposite(this, SWT.H_SCROLL | SWT.V_SCROLL );
            scImage.setLayoutData(new GridData(GridData.FILL_BOTH));
            viewLabel = new Label(scImage, SWT.NONE);
            scImage.setContent(viewLabel);
        }

        @Override
        void setChangeInfo(ChangeInfo changeInfo) {
            disposeImages();
            
            diagramModel = (IDiagramModel)changeInfo.getEObject(version);
            
            scale.setVisible(diagramModel != null);
            setScaledImage(diagramModel != null ? scale.getSelection() : 0);
        }
        
        void setScaledImage(int scaleValue) {
            Image image = null;
            
            if(scaleValue > 0 && diagramModel != null) {
                image = scaledImages.get(scaleValue);
                if(image == null) {
                    image = DiagramUtils.createImage(diagramModel, (double)scaleValue / SCALES, 5);
                    scaledImages.put(scaleValue, image);
                }
            }
            
            viewLabel.setImage(image);
            viewLabel.setSize(viewLabel.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        }
        
        void disposeImages() {
            if(scaledImages != null) {
                for(Image image : scaledImages.values()) {
                    if(!image.isDisposed()) {
                        image.dispose();
                    }
                }
            }
            
            scaledImages = new HashMap<>();
        }
    }

    // =====================================
    // Other
    // =====================================
    
    private void updateTabs(ChangeInfo changeInfo) {
        if(changeInfo == null || currentSelectedChangeInfo == changeInfo) {
            return;
        }
        
        currentSelectedChangeInfo = changeInfo;
        
        updateRevertButton(changeInfo);
        
        // If the eObject is a View add the View TabItem, else remove it
        EObject eObject = changeInfo.getDefaultEObject();
        if(eObject instanceof IDiagramModel) {
            if(itemView == null) {
                itemView = createViewTabItem();
            }
        }
        else if(itemView != null && !itemView.isDisposed()) {
            itemView.dispose();
            itemView = null;
        }
        
        // Update tab composites
        for(TabComposite c : fTabComposites) {
            c.setChangeInfo(changeInfo);
        }
    }

    private void updateRevertButton(ChangeInfo changeInfo) {
        int choice = changeInfo.getUserChoice();
        revertButton.setText(choice == ChangeInfo.KEEP ? 
                Messages.ReviewChangesDialog_26 :  // "Revert" 
                Messages.ReviewChangesDialog_25);  // "Keep"
    }
    
    @Override
    protected Point getDefaultDialogSize() {
        return new Point(800, 600);
    }
    
    @Override
    protected boolean isResizable() {
        return true;
    }

    // ===========================================================
    // Top Tree Control
    // ===========================================================
    
    private void createTreeControl(Composite parent) {
        Composite treeComp = new Composite(parent, SWT.BORDER);
        TreeColumnLayout treeLayout = new TreeColumnLayout();
        treeComp.setLayout(treeLayout);
        treeComp.setLayoutData(new GridData(GridData.FILL_BOTH));

        fTreeViewer = new TreeViewer(treeComp, SWT.FULL_SELECTION | SWT.MULTI);
        fTreeViewer.getControl().setLayoutData(new GridData(GridData.FILL_BOTH));
        fTreeViewer.getTree().setHeaderVisible(true);
        fTreeViewer.getTree().setLinesVisible(true);
        fTreeViewer.setComparator(new ViewerComparator(Collator.getInstance()) {
            @Override
            public int compare(Viewer viewer, Object object1, Object object2) {
                // Only compare ChangeInfo objects, not dependencies
                if(object1 instanceof ChangeInfo && object2 instanceof ChangeInfo) {
                    EObject eObject1 = ((ChangeInfo)object1).getDefaultEObject();
                    EObject eObject2 = ((ChangeInfo)object2).getDefaultEObject();
                    if(eObject1 == null || eObject2 == null) {
                        return 0;
                    }
                    String s1 = ArchiLabelProvider.INSTANCE.getDefaultName(eObject1.eClass());
                    String s2 = ArchiLabelProvider.INSTANCE.getDefaultName(eObject2.eClass());
                    return getComparator().compare(s1, s2);
                }
                return 0;
            }
        });

        // Columns
        TreeViewerColumn column1 = new TreeViewerColumn(fTreeViewer, SWT.NONE);
        column1.getColumn().setText(Messages.ReviewChangesDialog_21);  // "Type"
        treeLayout.setColumnData(column1.getColumn(), new ColumnWeightData(25, true));
        column1.setLabelProvider(new TypeColumnLabelProvider());

        TreeViewerColumn column2 = new TreeViewerColumn(fTreeViewer, SWT.NONE);
        column2.getColumn().setText(Messages.ReviewChangesDialog_22);  // "Name"
        treeLayout.setColumnData(column2.getColumn(), new ColumnWeightData(40, true));
        column2.setLabelProvider(new NameColumnLabelProvider());

        TreeViewerColumn column3 = new TreeViewerColumn(fTreeViewer, SWT.NONE);
        column3.getColumn().setText(Messages.ReviewChangesDialog_23);  // "Status"
        treeLayout.setColumnData(column3.getColumn(), new ColumnWeightData(15, true));
        column3.setLabelProvider(new StatusColumnLabelProvider());

        TreeViewerColumn column4 = new TreeViewerColumn(fTreeViewer, SWT.NONE);
        column4.getColumn().setText(Messages.ReviewChangesDialog_24);  // "Action"
        treeLayout.setColumnData(column4.getColumn(), new ColumnWeightData(15, true));
        column4.setLabelProvider(new ActionColumnLabelProvider());
        column4.setEditingSupport(new TreeComboChoiceEditingSupport(fTreeViewer));

        // Content Provider - hierarchical
        fTreeViewer.setContentProvider(new ITreeContentProvider() {
            @Override
            public Object[] getElements(Object inputElement) {
                return fHandler.getChangeInfos().toArray();
            }
            
            @Override
            public Object[] getChildren(Object parentElement) {
                if(parentElement instanceof ChangeInfo info) {
                    // Only show dependencies for diagrams that are set to REVERT
                    if(info.isDiagram() && info.isRevert() && info.hasDependencies()) {
                        return info.getDependencies().toArray();
                    }
                }
                return new Object[0];
            }
            
            @Override
            public Object getParent(Object element) {
                if(element instanceof DiagramDependencyInfo depInfo) {
                    return depInfo.getParent();
                }
                return null;
            }
            
            @Override
            public boolean hasChildren(Object element) {
                if(element instanceof ChangeInfo info) {
                    return info.isDiagram() && info.isRevert() && info.hasDependencies();
                }
                return false;
            }
        });

        // Tree Selection Listener
        fTreeViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                Object selected = ((StructuredSelection)event.getSelection()).getFirstElement();
                if(selected instanceof ChangeInfo info) {
                    // Don't set currentSelectedChangeInfo here - updateTabs does it
                    updateTabs(info);
                    updateRevertButton(info);
                } else if(selected instanceof DiagramDependencyInfo depInfo) {
                    // For dependencies, show parent diagram info but update button for dependency
                    updateTabs(depInfo.getParent());
                    updateRevertButtonForDependency(depInfo);
                }
            }
        });
        
        // Start the tree
        fTreeViewer.setInput(""); // anything will do //$NON-NLS-1$
    }
    
    /**
     * Update revert button text for a dependency
     */
    private void updateRevertButtonForDependency(DiagramDependencyInfo depInfo) {
        int choice = depInfo.getUserChoice();
        revertButton.setText(choice == DiagramDependencyInfo.RESTORE_CONCEPT ? 
                Messages.DiagramDependencyInfo_2 :  // "Remove from diagram" 
                Messages.DiagramDependencyInfo_0);  // "Restore element"
    }
    
    // ==================== Column Label Providers ====================
    
    private class TypeColumnLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if(element instanceof ChangeInfo info) {
                EObject eObject = info.getDefaultEObject();
                if(eObject != null) {
                    return ArchiLabelProvider.INSTANCE.getDefaultName(eObject.eClass());
                }
                String type = info.getElementType();
                return type != null ? type : Messages.ReviewChangesDialog_27;
            } else if(element instanceof DiagramDependencyInfo depInfo) {
                return depInfo.getConceptType();
            }
            return ""; //$NON-NLS-1$
        }
        
        @Override
        public Image getImage(Object element) {
            if(element instanceof ChangeInfo info) {
                EObject eObject = info.getDefaultEObject();
                if(eObject != null) {
                    return ArchiLabelProvider.INSTANCE.getImage(eObject);
                }
            } else if(element instanceof DiagramDependencyInfo depInfo) {
                return ArchiLabelProvider.INSTANCE.getImage(depInfo.getHeadConcept());
            }
            return null;
        }
    }
    
    private class NameColumnLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if(element instanceof ChangeInfo info) {
                EObject eObject = info.getDefaultEObject();
                if(eObject != null) {
                    return ArchiLabelProvider.INSTANCE.getLabel(eObject);
                }
                String id = info.getElementId();
                return id != null ? id : ""; //$NON-NLS-1$
            } else if(element instanceof DiagramDependencyInfo depInfo) {
                String name = depInfo.getConceptName();
                return name.isEmpty() ? "(unnamed)" : name; //$NON-NLS-1$
            }
            return ""; //$NON-NLS-1$
        }
    }
    
    private class StatusColumnLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if(element instanceof ChangeInfo info) {
                return info.getStatus();
            } else if(element instanceof DiagramDependencyInfo depInfo) {
                // Show reference count
                return depInfo.getVisualObjectCount() + " ref"; //$NON-NLS-1$
            }
            return ""; //$NON-NLS-1$
        }
    }
    
    private class ActionColumnLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if(element instanceof ChangeInfo info) {
                return choices[info.getUserChoice()];
            } else if(element instanceof DiagramDependencyInfo depInfo) {
                return depInfo.getActionDescription();
            }
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Combo Choice Editor for Action column - handles both ChangeInfo and DiagramDependencyInfo
     */
    private class TreeComboChoiceEditingSupport extends EditingSupport {
        private ComboBoxCellEditor changeInfoEditor;
        private ComboBoxCellEditor dependencyEditor;
        
        public TreeComboChoiceEditingSupport(ColumnViewer viewer) {
            super(viewer);
            changeInfoEditor = new ComboBoxCellEditor((Composite)viewer.getControl(), choices, SWT.READ_ONLY);
            dependencyEditor = new ComboBoxCellEditor((Composite)viewer.getControl(), dependencyChoices, SWT.READ_ONLY);
        }

        @Override
        protected CellEditor getCellEditor(Object element) {
            if(element instanceof DiagramDependencyInfo) {
                return dependencyEditor;
            }
            return changeInfoEditor;
        }

        @Override
        protected boolean canEdit(Object element) {
            // TODO: Enable when revert logic is complete
            return false;  // Temporarily disabled - all changes are kept
        }

        @Override
        protected Object getValue(Object element) {
            if(element instanceof ChangeInfo info) {
                return info.getUserChoice();
            } else if(element instanceof DiagramDependencyInfo depInfo) {
                return depInfo.getUserChoice();
            }
            return 0;
        }

        @Override
        protected void setValue(Object element, Object value) {
            Integer index = (Integer)value;
            if(index == -1) {
                return;
            }
            
            fTreeViewer.getTree().setRedraw(false);
            try {
                if(element instanceof ChangeInfo info) {
                    int oldChoice = info.getUserChoice();
                    
                    // Handle deleted concepts specially - need to cascade to diagrams
                    if(info.getChangeType() == ChangeInfo.DELETED && !info.isDiagram()) {
                        if(index == ChangeInfo.KEEP && oldChoice != ChangeInfo.KEEP) {
                            // Keeping deletion = concept stays deleted
                            // Must cascade to remove from all diagrams
                            info.setUserChoice(index);
                            cascadeConceptDeletionToDiagrams(info);
                        } else if(index == ChangeInfo.REVERT && oldChoice != ChangeInfo.REVERT) {
                            // Reverting deletion = concept is restored
                            // Diagram dependencies can now choose to restore
                            info.setUserChoice(index);
                            cascadeConceptRestoreToDiagrams(info);
                        } else {
                            info.setUserChoice(index);
                        }
                    } else {
                        info.setUserChoice(index);
                    }
                    
                    // If switching to REVERT on a diagram, analyze dependencies
                    if(info.getUserChoice() == ChangeInfo.REVERT && info.isDiagram() && !info.areDependenciesAnalyzed()) {
                        List<DiagramDependencyInfo> deps = fHandler.analyzeDiagramDependencies(info);
                        info.setDependencies(deps);
                    }
                    
                    // Expand to show dependencies
                    if(info.hasDependencies() && info.isRevert()) {
                        fTreeViewer.setExpandedState(element, true);
                    }
                    
                    updateRevertButton(info);
                } else if(element instanceof DiagramDependencyInfo depInfo) {
                    int oldChoice = depInfo.getUserChoice();
                    depInfo.setUserChoice(index);
                    
                    if(index == DiagramDependencyInfo.RESTORE_CONCEPT && oldChoice != DiagramDependencyInfo.RESTORE_CONCEPT) {
                        // Restoring a concept - must also restore in model
                        markConceptChangeInfoAsRevert(depInfo);
                        
                        // If restoring a relationship, also restore its source/target elements
                        if(!depInfo.isElement()) {
                            cascadeRestoreRelationshipEndpoints(depInfo);
                        }
                    } else if(index == DiagramDependencyInfo.REMOVE_FROM_DIAGRAM && oldChoice != DiagramDependencyInfo.REMOVE_FROM_DIAGRAM) {
                        // Removing an element from diagram - also remove connected relationships
                        if(depInfo.isElement()) {
                            cascadeRemoveRelationshipsForElement(depInfo);
                        }
                    }
                    
                    updateRevertButtonForDependency(depInfo);
                }
                
                // Always refresh the entire tree to show cascading changes
                fTreeViewer.refresh();
            } finally {
                fTreeViewer.getTree().setRedraw(true);
            }
        }
    }
}
