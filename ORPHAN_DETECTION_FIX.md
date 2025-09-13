# Orphan Detection Fix

## Problem

The original implementation of orphan detection was comparing XML IDs from local files with remote elements. This approach failed when users cloned or renamed files locally because:

1. When you clone/rename a file locally, the XML content (and IDs) remain the same
2. The system would find the same ID in both local and remote files
3. It would incorrectly conclude that the cloned file was not orphaned

## Solution

Changed the orphan detection logic to compare **file paths** instead of **XML IDs**:

### Before (Broken)
```clojure
(defn find-orphaned-files
  [app-conf]
  (let [remote-ids (get-remote-file-ids app-conf)  ; Get IDs from remote XML
        local-files (mi/api-files api local-path)]
    (filter (fn [file]
              (let [local-id (mi/find-id api (mxml/to-zip (slurp file)))]
                (not (contains? remote-ids local-id))))  ; Compare IDs
            local-files)))
```

### After (Fixed)
```clojure
(defn find-orphaned-files
  [app-conf]
  (let [expected-paths (get-remote-expected-file-paths app-conf)  ; Get expected file paths
        local-files (mi/api-files api local-path)]
    (filter (fn [file]
              (let [file-path (.getAbsolutePath file)]
                (not (contains? expected-paths file-path))))  ; Compare file paths
            local-files)))
```

## Key Changes

1. **`get-remote-expected-file-paths`**: Uses the same logic as the pull operation to generate the file paths that would be created from remote elements
2. **File path comparison**: Compares actual local file paths with expected file paths instead of XML IDs
3. **Leverages existing logic**: Uses `mi/deconstruct-node` to generate expected file paths, ensuring consistency with the pull operation

## How It Works

1. **Generate Expected Paths**: For each remote element, use `mi/deconstruct-node` to generate the file paths that would be created during a pull operation
2. **Get Local Files**: Get the actual local files from the filesystem
3. **Compare Paths**: Find local files whose paths are not in the set of expected paths
4. **Delete Orphans**: Delete the orphaned files (with confirmation in interactive mode)

## Testing

The fix includes comprehensive tests that verify:
- CLI flag parsing works correctly
- Orphan detection logic works with file path comparison
- Interactive confirmation works properly
- Integration with existing pull workflow

## Example Scenario

**Before Fix:**
- Remote has: `Channel1.xml` (ID: 123)
- Local has: `Channel1.xml` (ID: 123) and `Channel1-copy.xml` (ID: 123)
- System compares IDs: finds 123 in both local files and remote
- Result: No orphaned files detected ❌

**After Fix:**
- Remote has: `Channel1.xml` (ID: 123)
- Expected paths: `{"/path/to/Channel1.xml"}`
- Local files: `{"/path/to/Channel1.xml", "/path/to/Channel1-copy.xml"}`
- System compares paths: `Channel1-copy.xml` not in expected paths
- Result: `Channel1-copy.xml` detected as orphaned ✅

This fix ensures that cloned, renamed, or otherwise modified local files are properly detected as orphaned when they no longer correspond to remote elements.
