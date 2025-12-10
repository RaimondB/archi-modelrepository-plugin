/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.dialogs;

import static org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter;

import java.io.IOException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.ChangeSummary;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.archimatetool.editor.ui.UIUtils;
import com.archimatetool.editor.ui.components.ExtendedTitleAreaDialog;
import com.archimatetool.editor.utils.StringUtils;

/**
 * Commit (Save) Dialog
 * 
 * @author Phil Beauvoir
 */
public class CommitDialog extends ExtendedTitleAreaDialog {
    
    private static String DIALOG_ID = "CommitDialog"; //$NON-NLS-1$
    
    private Text fTextUserName, fTextUserEmail, fTextCommitMessage, fTextChangeSummary;
    private Button fAmendLastCommitCheckbox;
    
    private String fCommitMessage;
    private String fPreviousCommitMessage;
    private String fChangeSummaryText;
    private boolean fAmend;
    
    private IArchiRepository fRepository;
    
    public CommitDialog(Shell parentShell, IArchiRepository repo) {
        super(parentShell, DIALOG_ID);
        fRepository = repo;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.CommitDialog_0);
        setTitle(Messages.CommitDialog_0);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setMessage(Messages.CommitDialog_1, IMessageProvider.INFORMATION);
        setTitleImage(IModelRepositoryImages.ImageFactory.getImage(IModelRepositoryImages.BANNER_COMMIT));

        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(2, false);
        container.setLayout(layout);
        
        // Repo and branch
        String shortBranchName = "unknown"; //$NON-NLS-1$

        try {
            BranchStatus status = fRepository.getBranchStatus();
            if(status != null) {
                BranchInfo branchInfo = status.getCurrentLocalBranch();
                if(branchInfo != null) {
                    shortBranchName = branchInfo.getShortName();
                    fPreviousCommitMessage = branchInfo.getLatestCommit() != null ? branchInfo.getLatestCommit().getFullMessage() : null;
                }
            }
        }
        catch(IOException | GitAPIException ex) {
            ex.printStackTrace();
        }
                
        Label label = new Label(container, SWT.NONE);
        label.setText(Messages.CommitDialog_6);
        
        label = new Label(container, SWT.NONE);
        label.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        label.setText(fRepository.getName() + " [" + shortBranchName + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // User name & email
        String userName = ""; //$NON-NLS-1$
        String userEmail = ""; //$NON-NLS-1$
        
        try {
            PersonIdent result = fRepository.getUserDetails();
            userName = result.getName();
            userEmail = result.getEmailAddress();
        }
        catch(IOException ex) {
            ex.printStackTrace();
        }

        label = new Label(container, SWT.NONE);
        label.setText(Messages.CommitDialog_2);
        
        fTextUserName = UIUtils.createSingleTextControl(container, SWT.BORDER, false);
        fTextUserName.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        fTextUserName.setText(userName);
        
        label = new Label(container, SWT.NONE);
        label.setText(Messages.CommitDialog_3);
        
        fTextUserEmail = UIUtils.createSingleTextControl(container, SWT.BORDER, false);
        fTextUserEmail.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        fTextUserEmail.setText(userEmail);
        
        // Changes summary
        label = new Label(container, SWT.NONE);
        label.setText(Messages.CommitDialog_8); // "Changes to commit:"
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        label.setLayoutData(gd);
        
        fTextChangeSummary = new Text(container, SWT.BORDER | SWT.READ_ONLY | SWT.V_SCROLL | SWT.WRAP | SWT.MULTI);
        gd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
        gd.horizontalSpan = 2;
        gd.heightHint = 100; // Fixed height for changes summary
        gd.widthHint = 400;  // Minimum width
        fTextChangeSummary.setLayoutData(gd);
        fTextChangeSummary.setBackground(container.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        
        // Load and display change summary
        try {
            ChangeSummary summary = fRepository.getChangeSummary(20);
            fChangeSummaryText = summary.getSummaryText();
            fTextChangeSummary.setText(fChangeSummaryText);
        }
        catch(Exception ex) {
            fTextChangeSummary.setText(Messages.CommitDialog_9); // "Error loading changes"
            ex.printStackTrace();
        }
        
        label = new Label(container, SWT.NONE);
        label.setText(Messages.CommitDialog_4);
        gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        label.setLayoutData(gd);
        
        fTextCommitMessage = new Text(container, SWT.BORDER | SWT.V_SCROLL | SWT.WRAP | SWT.MULTI);
        gd = new GridData(GridData.FILL_BOTH);
        gd.horizontalSpan = 2;
        fTextCommitMessage.setLayoutData(gd);
        
        // Tab Traversal and Enter key
        UIUtils.applyTraverseListener(fTextCommitMessage, SWT.TRAVERSE_TAB_NEXT | SWT.TRAVERSE_TAB_PREVIOUS | SWT.TRAVERSE_RETURN);
        
        fAmendLastCommitCheckbox = new Button(container, SWT.CHECK);
        fAmendLastCommitCheckbox.setText(Messages.CommitDialog_5);
        gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        fAmendLastCommitCheckbox.setLayoutData(gd);
        
        try {
            // An amend of the last commit is allowed:
            // If HEAD and Remote Ref are not the same && the HEAD commit does not have more than one parent (i.e HEAD commit is not a merged commit)
            boolean isAmendable = !fRepository.isHeadAndRemoteSame() && getLatestLocalCommitParentCount() < 2;
            fAmendLastCommitCheckbox.setEnabled(isAmendable);
            
            // Set commit message to last commit message on checkbox click
            if(isAmendable && fPreviousCommitMessage != null) {
                fAmendLastCommitCheckbox.addSelectionListener(widgetSelectedAdapter(event -> {
                    if(fAmendLastCommitCheckbox.getSelection()) {
                        if(fTextCommitMessage.getText().isEmpty() || (!fTextCommitMessage.getText().equals(fPreviousCommitMessage) && MessageDialog.openQuestion(getShell(),
                                Messages.CommitDialog_0, Messages.CommitDialog_7))) {
                            fTextCommitMessage.setText(fPreviousCommitMessage);
                        }
                    }
                }));
            }
        }
        catch(IOException | GitAPIException ex) {
            ex.printStackTrace();
        }
        
        if(!StringUtils.isSet(userName)) {
            fTextUserName.setFocus();
        }
        else if(!StringUtils.isSet(userEmail)) {
            fTextUserEmail.setFocus();
        }
        else {
            fTextCommitMessage.setFocus();
        }
        
        return area;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }
    
    @Override
    protected Point getDefaultDialogSize() {
        return new Point(600, 450);
    }
    
    public String getCommitMessage() {
        return fCommitMessage;
    }
    
    public boolean getAmend() {
        return fAmend;
    }

    @Override
    protected void okPressed() {
        String userMessage = fTextCommitMessage.getText();
        
        // Append change summary after user's commit message
        // Format: user message, empty line, change summary
        if(userMessage != null && !userMessage.trim().isEmpty()) {
            fCommitMessage = userMessage;
            if(fChangeSummaryText != null && !fChangeSummaryText.isEmpty()) {
                fCommitMessage += "\n\n" + fChangeSummaryText; //$NON-NLS-1$
            }
        }
        else if(fChangeSummaryText != null && !fChangeSummaryText.isEmpty()) {
            // No user message, just use change summary
            fCommitMessage = fChangeSummaryText;
        }
        else {
            fCommitMessage = userMessage;
        }
        
        fAmend = fAmendLastCommitCheckbox.getSelection();
        
        // Store user name and email
        try {
            fRepository.saveUserDetails(fTextUserName.getText().trim(), fTextUserEmail.getText().trim());
        }
        catch(IOException ex) {
            ex.printStackTrace();
        }
        
        super.okPressed();
    }
    
    @Override
    protected Point getInitialSize() {
        return new Point(600, 700);
    }

    private int getLatestLocalCommitParentCount() throws IOException {
        try(Repository repository = Git.open(fRepository.getLocalRepositoryFolder()).getRepository()) {
            Ref head = repository.exactRef(IGraficoConstants.HEAD);
            if(head == null) {
                return 0;
            }
            
            ObjectId objectID = head.getObjectId();
            if(objectID == null) {
                return 0;
            }

            try(RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(objectID);
                revWalk.dispose();
                return commit.getParentCount();
            }
        }
    }
}