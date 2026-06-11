#!/usr/bin/env node

const { spawn, spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');

const JAR_NAME = 'mirthsync.jar';

function getJavaInstallInstructions() {
  const platform = process.platform;

  let instructions = `
Java is required to run mirthsync but was not found on your system.

`;

  switch (platform) {
    case 'darwin':
      instructions += `To install Java on macOS:

  Using Homebrew (recommended):
    brew install openjdk

  Or download from:
    https://adoptium.net/

  After installation, you may need to add Java to your PATH:
    echo 'export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"' >> ~/.zshrc
    source ~/.zshrc
`;
      break;

    case 'linux':
      instructions += `To install Java on Linux:

  Debian/Ubuntu:
    sudo apt update && sudo apt install default-jre

  Fedora/RHEL/CentOS:
    sudo dnf install java-latest-openjdk

  Arch Linux:
    sudo pacman -S jre-openjdk

  Or download from:
    https://adoptium.net/
`;
      break;

    case 'win32':
      instructions += `To install Java on Windows:

  Using winget:
    winget install Microsoft.OpenJDK.21

  Using Chocolatey:
    choco install openjdk

  Or download from:
    https://adoptium.net/

  After installation, you may need to restart your terminal.
`;
      break;

    default:
      instructions += `Please install Java JRE or JDK version 8 or higher.
Download from: https://adoptium.net/
`;
  }

  instructions += `
Minimum required version: Java 8
Recommended: Java 11, 17, or 21 (LTS versions)
`;

  return instructions;
}

function findJava() {
  // Check if java is in PATH
  const javaCommand = process.platform === 'win32' ? 'java.exe' : 'java';

  // Try running java -version to check if it's available. As with the
  // main spawn below, do not use a shell here: it is unnecessary for a
  // no-argument detection call, and a shell launched when java is absent
  // can still write to stderr, which would be misread as "java present".
  const result = spawnSync(javaCommand, ['-version'], {
    stdio: ['pipe', 'pipe', 'pipe'],
    encoding: 'utf8'
  });

  if (!result.error && (result.status === 0 || result.stderr)) {
    // Java outputs version to stderr
    return javaCommand;
  }

  // Check JAVA_HOME
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaPath = path.join(javaHome, 'bin', javaCommand);
    if (fs.existsSync(javaPath)) {
      const homeResult = spawnSync(javaPath, ['-version'], {
        stdio: ['pipe', 'pipe', 'pipe'],
        encoding: 'utf8'
      });
      if (!homeResult.error && (homeResult.status === 0 || homeResult.stderr)) {
        return javaPath;
      }
    }
  }

  return null;
}

function getJarPath() {
  // The JAR is in ../lib/ relative to this script
  return path.join(__dirname, '..', 'lib', JAR_NAME);
}

function main() {
  // Find Java
  const javaPath = findJava();

  if (!javaPath) {
    console.error(getJavaInstallInstructions());
    process.exit(1);
  }

  // Find the JAR file
  const jarPath = getJarPath();

  if (!fs.existsSync(jarPath)) {
    console.error(`Error: mirthsync JAR file not found at ${jarPath}`);
    console.error('This may indicate a corrupted installation. Try reinstalling:');
    console.error('  npm uninstall -g @saga-it/mirthsync');
    console.error('  npm install -g @saga-it/mirthsync');
    process.exit(1);
  }

  // Build the command arguments
  const args = ['-jar', jarPath, ...process.argv.slice(2)];

  // Spawn Java process with inherited stdio for full interactivity.
  //
  // Do NOT run this through a shell. With shell:true on Windows, Node
  // concatenates the argv into a single command string without quoting
  // (see Node's DEP0190 warning), so any argument containing a space —
  // e.g. -r "Channels/Default Group/Sample Channel" — is word-split by the
  // shell before Java ever sees it, and mirthsync fails to parse the command.
  // Passing the argv array directly lets the runtime deliver each argument to
  // Java verbatim, spaces and all, identically on every platform. A shell was
  // never needed for resolution: findJava() always returns a concrete
  // executable name ("java.exe") or absolute path, which spawn() locates on
  // PATH on its own.
  const child = spawn(javaPath, args, {
    stdio: 'inherit'
  });

  // Forward signals to child process
  const signals = ['SIGINT', 'SIGTERM', 'SIGHUP'];
  signals.forEach(signal => {
    process.on(signal, () => {
      if (!child.killed) {
        child.kill(signal);
      }
    });
  });

  // Exit with the same code as the Java process
  child.on('close', (code) => {
    process.exit(code || 0);
  });

  child.on('error', (err) => {
    console.error(`Failed to start mirthsync: ${err.message}`);
    process.exit(1);
  });
}

main();
