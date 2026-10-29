# File Dupe Detector

A Java desktop application that detects duplicate files across local drives, removable volumes,
and cloud-storage folders (as mounted/synced to the local file system). Duplicates are identified
by comparing file size first, then a SHA-1 content hash. Scan results are persisted in a local
embedded database so re-scans don't need to recompute hashes for unchanged files. A Swing GUI
visualizes scanned locations and duplicates in a tree view.

See [`specs/001-duplicate-file-detector/`](specs/001-duplicate-file-detector/) for the full
feature specification, implementation plan, data model, and design decisions.

## Prerequisites

- Java 17+ (JDK)
- Maven 3.8+

## Build

```powershell
mvn clean package
```

This produces a runnable "fat" JAR at `target/file-dupe-detector-1.0.0-SNAPSHOT.jar` (bundles the
embedded H2 database driver and SLF4J logging).

## Run

### GUI (default)

```powershell
java -jar target/file-dupe-detector-1.0.0-SNAPSHOT.jar
```

Opens the main window. Use **Add Scan Location...** to register one or more folders (local,
removable, or cloud-synced), then click **Scan**. The tree view shows scanned locations and their
folder/file hierarchy; files that are part of a duplicate group are visually highlighted. Select
a duplicate file to see all other members of its group (full path + source location) in the detail
panel on the right.

Persisted data (scan locations, file records, computed hashes) lives in a local embedded database
under `~/.file-dupe-detector/` and survives application restarts, so previously discovered
duplicates are available immediately without a new scan.

### Console smoke-test harness (no display required)

```powershell
java -jar target/file-dupe-detector-1.0.0-SNAPSHOT.jar <folder1> [folder2] ...
```

Registers the given folders as scan locations, runs a scan synchronously, and prints resulting
duplicate groups (with full relative paths) to stdout. Useful for quick, scripted verification.

## Validating the implementation

See [`specs/001-duplicate-file-detector/quickstart.md`](specs/001-duplicate-file-detector/quickstart.md)
for step-by-step validation scenarios covering duplicate detection, re-scan persistence/speedup,
tree-view navigation, and resilience to unavailable locations / inaccessible files.

## Running tests

```powershell
mvn test
```

Unit and integration tests cover hashing, size pre-filtering, duplicate grouping, change
detection on re-scan, missing-file detection, and GUI tree-model/selection logic (headless).

# pCloud

# How to get the client-ID?

create a new app under: https://docs.pcloud.com/my_apps/



# How to get an access token (oauth2)

see https://docs.pcloud.com/methods/oauth_2.0/authorize.html


## query code

In a webbrowser enter the url with the client id of the application:

```
https://my.pcloud.com/oauth2/authorize?client_id=<client_id>&response_type=code
```

Response after login:

```
Your access code is:
<access_code>
Hostname:
eapi.pcloud.com
Please enter this code into the application.
This code will expire after 600 seconds.
```


## query access token for code

```
https://eapi.pcloud.com/oauth2_token?client_id=<client_di>&client_secret=<client_secret>&code=<code> 
```

Response:

```
{
  "result": 0,
  "userid": 1273851,
  "locationid": 2,
  "token_type": "bearer",
  "access_token": "..."
}
```

