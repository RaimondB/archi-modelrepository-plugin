/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.authentication;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.authentication.internal.EncryptedCredentialsStorage;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.preferences.IPreferenceConstants;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;


/**
 * Authenticator for SSH and HTTP
 * 
 * @author Phillip Beauvoir
 */
public final class CredentialsAuthenticator {
    
    public interface SSHIdentityProvider {
        File getIdentityFile();
        char[] getIdentityPassword() throws IOException, GeneralSecurityException;
    }
    
    static {
        /**
         * Set the SshSessionFactory instance to our specialised SshSessionFactory 
         */
        SshSessionFactory.setInstance(new CustomSshSessionFactory());
    }
    
    /**
     * SSH Identity Provider. Default is with details from Prefs
     */
    private static SSHIdentityProvider sshIdentityProvider = new SSHIdentityProvider() {
        @Override
        public File getIdentityFile() {
            if(Platform.getPreferencesService() != null) { // Check Preference Service is running in case background fetch is running and we quit the app
                return new File(ModelRepositoryPlugin.getInstance().getPreferenceStore().getString(IPreferenceConstants.PREFS_SSH_IDENTITY_FILE)); 
            }
            
            return null;
        }
        
        @Override
        public char[] getIdentityPassword() throws IOException, GeneralSecurityException {
            char[] password = null;
            
            if(Platform.getPreferencesService() != null // Check Preference Service is running in case background fetch is running and we quit the app
                    && ModelRepositoryPlugin.getInstance().getPreferenceStore().getBoolean(IPreferenceConstants.PREFS_SSH_IDENTITY_REQUIRES_PASSWORD)) {
                
                EncryptedCredentialsStorage cs = new EncryptedCredentialsStorage(
                        new File(ModelRepositoryPlugin.getInstance().getUserModelRepositoryFolder(), IGraficoConstants.SSH_CREDENTIALS_FILE));

                if(cs.hasCredentialsFile()) {
                    password = cs.getPassword();
                }
                else {
                    throw new IOException(Messages.CredentialsAuthenticator_1);
                }
            }
            
            return password;
        }
    };
    
    public static void setSSHIdentityProvider(SSHIdentityProvider sshIdentityProvider) {
        CredentialsAuthenticator.sshIdentityProvider = sshIdentityProvider;
    }
    
    public static SSHIdentityProvider getSSHIdentityProvider() {
        return sshIdentityProvider;
    }
    
    // =========================================================================
    // Central Auth-Method Query API
    // =========================================================================
    
    /**
     * Whether the user has selected Git Credential Manager (GCM) as their HTTP auth method.
     */
    public static boolean isGCMAuthEnabled() {
        return IPreferenceConstants.HTTP_AUTH_GCM.equals(
                ModelRepositoryPlugin.getInstance().getPreferenceStore()
                        .getString(IPreferenceConstants.PREFS_HTTP_AUTH_METHOD));
    }
    
    /**
     * Ensure the primary encryption key is available (prompting the user if needed).
     * GCM users skip this entirely — they don't need the primary key since
     * credentials are managed by the OS credential store.
     * 
     * @return {@code true} if the key is set or not needed, {@code false} if the user cancelled
     */
    public static boolean checkPrimaryKeyIfNeeded() throws IOException, GeneralSecurityException {
        if(isGCMAuthEnabled()) {
            return true;
        }
        return EncryptedCredentialsStorage.checkPrimaryKeySet();
    }
    
    /**
     * Quick check whether the primary key is already loaded (no user prompt).
     * GCM users always return {@code true} since they don't need one.
     */
    public static boolean isPrimaryKeyReady() {
        return isGCMAuthEnabled() || EncryptedCredentialsStorage.isPrimaryKeySet();
    }
    
    /**
     * Whether the given URL requires the user to provide credentials explicitly
     * (via a dialog or stored file). Returns {@code true} only for HTTP URLs
     * when PAT mode is selected. SSH and GCM handle credentials automatically.
     * 
     * <p>Use this instead of checking {@code isHTTP(url) && !isGCMAuthEnabled()}
     * everywhere.</p>
     */
    public static boolean requiresExplicitCredentials(String repoURL) {
        return GraficoUtils.isHTTP(repoURL) && !isGCMAuthEnabled();
    }
    
    /**
     * Whether credentials should be stored in encrypted storage after a successful
     * clone/create operation. Only applies to HTTP+PAT when the store preference
     * is enabled. GCM manages its own credential cache in the OS store.
     */
    public static boolean shouldStoreCredentials(String repoURL) {
        return requiresExplicitCredentials(repoURL)
                && ModelRepositoryPlugin.getInstance().getPreferenceStore()
                        .getBoolean(IPreferenceConstants.PREFS_STORE_REPO_CREDENTIALS);
    }
    
    /**
     * Get credentials for the given URL without showing a dialog.
     * <ul>
     *   <li>SSH → returns {@code null} (SSH agent handles auth)</li>
     *   <li>HTTP + GCM → returns credentials from Git Credential Manager</li>
     *   <li>HTTP + PAT → returns stored credentials if available, {@code null} otherwise</li>
     * </ul>
     * 
     * @param repoURL the repository URL
     * @param repo    the repository (used to locate stored PAT credentials; may be {@code null} for clone)
     * @return credentials, or {@code null} if none available without user interaction
     */
    public static UsernamePassword getNonInteractiveCredentials(String repoURL, IArchiRepository repo)
            throws IOException, GeneralSecurityException {
        if(GraficoUtils.isSSH(repoURL)) {
            return null;
        }
        
        if(isGCMAuthEnabled()) {
            return getGCMCredentials(repoURL);
        }
        
        // PAT mode — try stored credentials
        if(repo != null) {
            boolean doStore = ModelRepositoryPlugin.getInstance().getPreferenceStore()
                    .getBoolean(IPreferenceConstants.PREFS_STORE_REPO_CREDENTIALS);
            EncryptedCredentialsStorage cs = EncryptedCredentialsStorage.forRepository(repo);
            if(doStore && cs.hasCredentialsFile()) {
                return cs.getUsernamePassword();
            }
        }
        
        return null;
    }
    
    /**
     * Get credentials for a clone or create-repo operation where a dialog may have
     * collected username/password from the user.
     * <ul>
     *   <li>SSH → returns {@code null} (SSH agent handles auth)</li>
     *   <li>HTTP + GCM → returns credentials from Git Credential Manager (dialog creds ignored)</li>
     *   <li>HTTP + PAT → returns the dialog-provided credentials as-is</li>
     * </ul>
     * 
     * @param repoURL            the repository URL to clone/push to
     * @param dialogCredentials  credentials collected from the clone/create dialog (used only for PAT)
     * @return credentials, or {@code null} if none needed (SSH)
     */
    public static UsernamePassword getCloneCredentials(String repoURL, UsernamePassword dialogCredentials)
            throws IOException, GeneralSecurityException {
        if(requiresExplicitCredentials(repoURL)) {
            return dialogCredentials;
        }
        return getNonInteractiveCredentials(repoURL, null);
    }
    
    /**
     * Whether the given credentials are sufficient for the URL's auth method.
     * Returns {@code true} for SSH and GCM (no user-provided credentials needed),
     * or for HTTP+PAT if the credentials have at least a username or password.
     * 
     * @param repoURL the repository URL
     * @param npw     credentials to validate (may be {@code null})
     * @return {@code true} if credentials are valid or not required
     */
    public static boolean hasValidCredentials(String repoURL, UsernamePassword npw) {
        if(!requiresExplicitCredentials(repoURL)) {
            return true;
        }
        if(npw == null) {
            return false;
        }
        String username = npw.getUsername();
        boolean hasUsername = username != null && !username.isBlank();
        boolean hasPassword = npw.getPassword() != null && npw.getPassword().length > 0;
        return hasUsername || hasPassword;
    }
    
    /**
     * Factory method to get the TransportConfigCallback for authentication for repoURL
     * npw can be null and is ignored if repoURL is SSH
     */
    public static TransportConfigCallback getTransportConfigCallback(String repoURL, UsernamePassword npw) {
        return new TransportConfigCallback() {
            @Override
            public void configure(Transport transport) {
                transport.setRemoveDeletedRefs(true); // Delete remote branches that we don't have
                
                // SSH
                if(GraficoUtils.isSSH(repoURL)) {
                    transport.setCredentialsProvider(new SSHCredentialsProvider());
                }
                // HTTP
                else if(npw != null) {
                    transport.setCredentialsProvider(new UsernamePasswordCredentialsProvider(npw.getUsername(), npw.getPassword()));
                }
            }
        };
    }
    
    /**
     * Retrieve credentials from Git Credential Manager (GCM) for the given repository URL.
     * Uses the {@code git credential fill} protocol to query GCM for cached or interactive
     * credentials (OAuth, browser login, PAT from OS credential store).
     * 
     * <p>This bridges GCM with JGit: GCM handles the authentication flow, and the resulting
     * credentials are returned as a {@link UsernamePassword} that JGit can use.</p>
     * 
     * @param repoURL the HTTPS repository URL
     * @return credentials from GCM, or null if GCM is not available or the URL cannot be parsed
     * @throws IOException if the credential fill process fails (e.g. auth denied)
     */
    public static UsernamePassword getGCMCredentials(String repoURL) throws IOException {
        try {
            // Parse the URL to extract protocol and host
            URI uri = URI.create(repoURL.replaceAll("\\.git$", "")); //$NON-NLS-1$ //$NON-NLS-2$
            String protocol = uri.getScheme(); // "https" or "http"
            String host = uri.getHost();
            
            if(protocol == null || host == null) {
                return null;
            }
            
            // Build credential fill input
            // The git credential protocol expects: protocol, host, and optionally path
            StringBuilder input = new StringBuilder();
            input.append("protocol=").append(protocol).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            input.append("host=").append(host).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            String path = uri.getPath();
            if(path != null && !path.isEmpty()) {
                // Remove leading slash
                if(path.startsWith("/")) { //$NON-NLS-1$
                    path = path.substring(1);
                }
                input.append("path=").append(path).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            input.append("\n"); // Empty line terminates input //$NON-NLS-1$
            
            ProcessBuilder pb = new ProcessBuilder("git", "credential", "fill"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            pb.redirectErrorStream(false);
            
            Process process = pb.start();
            
            // Send the credential request via stdin
            try(OutputStream os = process.getOutputStream()) {
                os.write(input.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            
            // Read credentials from stdout
            String username = null;
            String password = null;
            
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    if(line.startsWith("username=")) { //$NON-NLS-1$
                        username = line.substring("username=".length()); //$NON-NLS-1$
                    }
                    else if(line.startsWith("password=")) { //$NON-NLS-1$
                        password = line.substring("password=".length()); //$NON-NLS-1$
                    }
                }
            }
            
            // Drain stderr
            try(BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                while(errReader.readLine() != null) { /* drain */ }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("git credential fill failed (exit " + exitCode + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            if(username != null && password != null) {
                return new UsernamePassword(username, password.toCharArray());
            }
            
            return null;
        }
        catch(IOException ex) {
            String message = ex.getMessage();
            if(message != null && (message.contains("Cannot run program") || message.contains("not found"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return null; // Git not available
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("git credential fill interrupted", ex); //$NON-NLS-1$
        }
    }
}
