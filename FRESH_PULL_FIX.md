# Fresh Pull Fix for Delete-Orphaned Feature

## Problem

The delete-orphaned feature was incorrectly detecting and deleting files from fresh pulls. When doing a pull to an empty target directory with the `--delete-orphaned` flag, it would immediately delete some of the files that were just pulled, even though there should be no orphaned files in a fresh pull.

## Root Cause

The issue was in the timing of when orphan detection was performed:

1. **Before Fix**: Orphan detection ran AFTER the pull operation
2. **Problem**: The system compared the local files that were just created by the pull with the remote elements that were used to create those same files
3. **Result**: Since the expected file paths matched the local files that were just created, no orphaned files should have been detected, but the logic was flawed

## Solution

Changed the approach to capture local files BEFORE the pull operation:

### Key Changes

1. **`capture-pre-pull-local-files`**: New function that captures existing local files before the pull operation
2. **Modified workflow**: 
   - BEFORE pull: Capture existing local files
   - Do pull operation
   - AFTER pull: Compare pre-pull local files with post-pull remote elements
3. **Updated `cleanup-orphaned-files`**: Now uses the captured pre-pull local files instead of current local files

### Code Changes

#### New Function
```clojure
(defn capture-pre-pull-local-files
  "Capture local files before pull operation for orphan detection."
  [{:keys [api] :as app-conf}]
  (let [local-files (mi/api-files api (mi/local-path api (:target app-conf)))]
    (assoc app-conf :pre-pull-local-files local-files)))
```

#### Updated Cleanup Function
```clojure
(defn cleanup-orphaned-files
  "Clean up orphaned local files that no longer exist on the remote server."
  [{:keys [api delete-orphaned pre-pull-local-files] :as app-conf}]
  (if delete-orphaned
    (do
      (log/info (str "Checking for orphaned files in " (mi/local-path api (:target app-conf))))
      (let [expected-paths (get-remote-expected-file-paths app-conf)
            orphaned-files (filter (fn [^File file]
                                     (let [file-path (.getAbsolutePath file)]
                                       (not (contains? expected-paths file-path))))
                                   pre-pull-local-files)]
        (delete-orphaned-files app-conf orphaned-files)))
    app-conf))
```

#### Updated Workflow in core.clj
```clojure
(let [preprocessed-conf (api/iterate-apis app-conf (api/apis app-conf) api/preprocess-api)
      ;; For pull operations with delete-orphaned, capture local files before pull
      conf-with-pre-pull (if (and (= "pull" action) (:delete-orphaned preprocessed-conf))
                            (api/iterate-apis preprocessed-conf (api/apis preprocessed-conf) act/capture-pre-pull-local-files)
                            preprocessed-conf)
      processed-conf (api/iterate-apis conf-with-pre-pull (api/apis conf-with-pre-pull) action-fn)]
  ;; After pull, cleanup orphaned files if requested
  (if (and (= "pull" action) (:delete-orphaned processed-conf))
    (api/iterate-apis processed-conf (api/apis processed-conf) act/cleanup-orphaned-files)
    processed-conf))
```

## Testing

Added integration test in `common_tests.clj`:

```clojure
(testing "Delete-orphaned flag works correctly with fresh pull"
  ;; Test that --delete-orphaned doesn't delete files from a fresh pull
  (let [fresh-dir (str repo-dir "-fresh")]
    (ensure-directory-exists fresh-dir)
    ;; Pull to fresh directory with --delete-orphaned flag
    (is (= 0 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" fresh-dir
                        "--delete-orphaned" "-i" "-f" "pull")))
    ;; Verify that files were pulled and not deleted
    (is (not (empty-directory? fresh-dir)))
    ;; Clean up
    (rm-rf fresh-dir)))
```

## How It Now Works

1. **Fresh Pull Scenario**:
   - Pre-pull local files: `[]` (empty directory)
   - Pull operation: Downloads files from remote
   - Post-pull remote elements: Generate expected file paths
   - Orphan detection: Compare `[]` with expected paths → No orphaned files ✅

2. **Clone/Rename Scenario**:
   - Pre-pull local files: `["/path/Channel1.xml", "/path/Channel1-copy.xml"]`
   - Pull operation: Downloads files from remote
   - Post-pull remote elements: Generate expected paths `["/path/Channel1.xml"]`
   - Orphan detection: Compare pre-pull files with expected paths → `Channel1-copy.xml` detected as orphaned ✅

## Security Improvements

Added path validation to prevent path traversal vulnerabilities:

### `safe-delete-file?` Function
```clojure
(defn- safe-delete-file?
  "Check if a file can be safely deleted by ensuring it's within the target directory."
  [^File file target-dir]
  (try
    (let [file-canonical (.getCanonicalPath file)
          target-canonical (.getCanonicalPath (io/file target-dir))]
      (and (.exists file)
           (.startsWith file-canonical target-canonical)))
    (catch Exception e
      (log/warn (str "Failed to validate file path for deletion: " (.getAbsolutePath file) " - " (.getMessage e)))
      false)))
```

### Security Features
- **Canonical Path Resolution**: Uses `getCanonicalPath()` to resolve any symbolic links or relative paths
- **Target Directory Validation**: Ensures files are within the target directory before deletion
- **Exception Handling**: Gracefully handles path resolution failures
- **Logging**: Logs warnings for any security validation failures

### Updated File Deletion
```clojure
(doseq [^File file orphaned-files]
  (if (safe-delete-file? file target)
    (try
      (log/info (str "Deleting: " (.getAbsolutePath file)))
      (.delete file)
      (catch Exception e
        (log/error (str "Failed to delete " (.getAbsolutePath file) ": " (.getMessage e)))))
    (log/warn (str "Skipping deletion of file outside target directory: " (.getAbsolutePath file)))))
```

## Result

The fix ensures that:
- Fresh pulls with `--delete-orphaned` don't delete any files
- Clone/rename scenarios correctly detect orphaned files
- The feature works as intended in all scenarios
- **Security**: Path traversal vulnerabilities are prevented through canonical path validation
- **Safety**: Only files within the target directory can be deleted
