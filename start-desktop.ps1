$code = @"
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

public class InteractiveLauncher {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO {
        public Int32 cb;
        public string lpReserved;
        public string lpDesktop;
        public string lpTitle;
        public Int32 dwX;
        public Int32 dwY;
        public Int32 dwXSize;
        public Int32 dwYSize;
        public Int32 dwXCountChars;
        public Int32 dwYCountChars;
        public Int32 dwFillAttribute;
        public Int32 dwFlags;
        public Int16 wShowWindow;
        public Int16 cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION {
        public IntPtr hProcess;
        public IntPtr hThread;
        public Int32 dwProcessId;
        public Int32 dwThreadId;
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern bool CreateProcess(
        string lpApplicationName,
        string lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        string lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation);

    public static int Launch(string appPath, string cmdLine, string workingDir) {
        STARTUPINFO si = new STARTUPINFO();
        si.cb = Marshal.SizeOf(si);
        si.lpDesktop = @"WinSta0\Default";
        
        PROCESS_INFORMATION pi = new PROCESS_INFORMATION();
        // CREATE_NEW_CONSOLE = 0x10
        uint flags = 0x00000010;
        bool success = CreateProcess(appPath, cmdLine, IntPtr.Zero, IntPtr.Zero, false, flags, IntPtr.Zero, workingDir, ref si, out pi);
        if (!success) {
            return -Marshal.GetLastWin32Error();
        }
        return pi.dwProcessId;
    }
}
"@
Add-Type -TypeDefinition $code

$app = "C:\Windows\System32\cmd.exe"
$cmd = 'cmd.exe /k "C:\Users\dhruv\Downloads\EchoMusic-Desktop\run.bat"'
$dir = "C:\Users\dhruv\Downloads\EchoMusic-Desktop"
$pidResult = [InteractiveLauncher]::Launch($app, $cmd, $dir)
Write-Output "Launched process PID: $pidResult"
