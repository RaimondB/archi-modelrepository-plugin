/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.preferences;




/**
 * Constant definitions for plug-in preferences
 * 
 * @author Phillip Beauvoir
 */
@SuppressWarnings("nls")
public interface IPreferenceConstants {
    
    String PREFS_COMMIT_USER_NAME = "userName";
    String PREFS_COMMIT_USER_EMAIL = "userEmail";
    String PREFS_REPOSITORY_FOLDER = "repoFolder";
    String PREFS_SCAN_REPOSITORY_FOLDER = "scanRepoFolder";
    String PREFS_SSH_IDENTITY_FILE = "sshIdentityFile";
    String PREFS_SSH_IDENTITY_REQUIRES_PASSWORD = "sshIdentityRequiresPassword";
    String PREFS_SSH_SCAN_DIR = "sshScanSshDir";
    String PREFS_STORE_REPO_CREDENTIALS = "storeCredentials";
    
    /**
     * HTTP authentication method: "httpPat" (default, backward-compatible) or "httpGcm" (Git Credential Manager).
     * This only applies to HTTP/HTTPS repositories. SSH repositories always use SSH keys.
     */
    String PREFS_HTTP_AUTH_METHOD = "httpAuthMethod";
    
    /**
     * Value for PREFS_HTTP_AUTH_METHOD: use username/password or personal access token (PAT) via JGit.
     * This is the default and backward-compatible option.
     */
    String HTTP_AUTH_PAT = "httpPat";
    
    /**
     * Value for PREFS_HTTP_AUTH_METHOD: use Git Credential Manager via native git.
     * GCM handles OAuth, SSO, PAT caching, and browser-based authentication flows.
     */
    String HTTP_AUTH_GCM = "httpGcm";
    
    /**
     * Whether the user has been asked about GCM onboarding.
     * Set to true after the first-run GCM detection dialog, regardless of user choice.
     */
    String PREFS_GCM_ONBOARDING_SHOWN = "gcmOnboardingShown";
    
    String PREFS_PROXY_USE = "proxyUse";
    String PREFS_PROXY_HOST = "proxyHost";
    String PREFS_PROXY_PORT = "proxyPort";
    String PREFS_PROXY_REQUIRES_AUTHENTICATION = "proxyAuthenticate";
    
    String PREFS_EXPORT_MAX_THREADS = "exportMaxThreads";
    
    String PREFS_USE_NATIVE_GIT = "useNativeGit";
    
    String PREFS_FETCH_IN_BACKGROUND = "fetchInBackground";
    String PREFS_FETCH_IN_BACKGROUND_INTERVAL = "fetchInBackgroundInterval";
    
    /*
       Password constraints
    
       Can be set in plugin_customization.ini as:
          org.archicontribs.modelrepository/passwordMinLength=10
          org.archicontribs.modelrepository/passwordMinLowerCase=2
          org.archicontribs.modelrepository/passwordMinUpperCase=2
          org.archicontribs.modelrepository/passwordMinDigits=2
          org.archicontribs.modelrepository/passwordMinSpecialChars=2
    */
    
    String PREFS_PASSWORD_MIN_LENGTH = "passwordMinLength";
    String PREFS_PASSWORD_MIN_LOWERCASE_CHARS = "passwordMinLowerCase";
    String PREFS_PASSWORD_MIN_UPPERCASE_CHARS = "passwordMinUpperCase";
    String PREFS_PASSWORD_MIN_DIGITS = "passwordMinDigits";
    String PREFS_PASSWORD_MIN_SPECIAL_CHARS = "passwordMinSpecialChars";
    
    /*
      Password timeouts
      
      Can be set in plugin_customization.ini as minutes:
         org.archicontribs.modelrepository/passwordPrimaryTimeout=10
         org.archicontribs.modelrepository/passwordInactivityTimeout=10
     */
    
    String PREFS_PRIMARY_PASSWORD_TIMEOUT = "passwordPrimaryTimeout";
    String PREFS_PASSWORD_INACTIVITY_TIMEOUT = "passwordInactivityTimeout";
 }
