// oieservice.exe: the Windows service program and the oieservice command, like install4j's.
// The Service Control Manager starts it without arguments. It then runs the engine in the foreground
// and stops it with a console Ctrl+C, so the engine's shutdown hooks run. From a command prompt it
// runs the oieservice verbs. Both use the engine folder's launcher runtime and EngineBootstrap.
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.ServiceProcess;
using System.Text;
using System.Threading;

[assembly: System.Reflection.AssemblyTitle("Open Integration Engine Service")]
[assembly: System.Reflection.AssemblyProduct("Open Integration Engine")]

static class Program
{
    internal const string ServiceName = "Open Integration Engine Service";

    const uint ScManagerCreateService = 0x0002, ServiceAllAccess = 0xF01FF, ServiceWin32OwnProcess = 0x10, ServiceAutoStart = 2, ServiceErrorNormal = 1;
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern IntPtr OpenSCManager(string machine, string database, uint access);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr CreateService(IntPtr manager, string name, string displayName, uint access, uint type, uint start, uint errorControl,
        string path, string group, IntPtr tag, string dependencies, string account, string password);
    [DllImport("advapi32.dll")] static extern bool CloseServiceHandle(IntPtr handle);

    static int Main(string[] args)
    {
        string home = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
        if (args.Length == 1 && args[0] == "--create-service") return Register(home);
        if (args.Length == 0 && !Environment.UserInteractive)
        {
            ServiceBase.Run(new EngineService(home));
            return 0;
        }
        var start = Bootstrap(home, args);
        using (var process = Process.Start(start))
        {
            process.WaitForExit();
            return process.ExitCode;
        }
    }

    /// <summary>install4j's service: automatic, Local System, no description or failure actions, running this program.</summary>
    static int Register(string home)
    {
        IntPtr manager = OpenSCManager(null, null, ScManagerCreateService);
        if (manager == IntPtr.Zero) { Console.Error.WriteLine(new Win32Exception(Marshal.GetLastWin32Error()).Message); return 1; }
        try
        {
            string path = "\"" + Path.Combine(home, "oieservice.exe") + "\"";
            IntPtr service = CreateService(manager, ServiceName, ServiceName, ServiceAllAccess, ServiceWin32OwnProcess, ServiceAutoStart, ServiceErrorNormal,
                path, null, IntPtr.Zero, null, null, null);
            if (service == IntPtr.Zero) { Console.Error.WriteLine(new Win32Exception(Marshal.GetLastWin32Error()).Message); return 1; }
            CloseServiceHandle(service);
            return 0;
        }
        finally { CloseServiceHandle(manager); }
    }

    /// <summary>The engine launcher's Java process: EngineBootstrap in the oieservice role.</summary>
    internal static ProcessStartInfo Bootstrap(string home, string[] args)
    {
        string app = Path.Combine(home, "app");
        var line = new StringBuilder();
        Append(line, "-Doie.engine.home=" + home);
        Append(line, "-Doie.engine.role=service");
        Append(line, "-cp");
        Append(line, Path.Combine(app, "oie-installer-jpackage.jar") + ";" + Path.Combine(app, "lib", "*"));
        Append(line, "org.openintegrationengine.installer.EngineBootstrap");
        foreach (string arg in args) Append(line, arg);
        return new ProcessStartInfo(Path.Combine(home, "runtime", "bin", "java.exe"), line.ToString()) { UseShellExecute = false, WorkingDirectory = home };
    }

    /// <summary>Quote one argument for CommandLineToArgvW, as the C runtime parses it.</summary>
    static void Append(StringBuilder line, string arg)
    {
        if (line.Length > 0) line.Append(' ');
        if (arg.Length > 0 && arg.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) { line.Append(arg); return; }
        line.Append('"');
        int slashes = 0;
        foreach (char c in arg)
        {
            if (c == '\\') { slashes++; continue; }
            line.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
            slashes = 0;
            line.Append(c);
        }
        line.Append('\\', slashes * 2).Append('"');
    }
}

sealed class EngineService : ServiceBase
{
    const uint CtrlCEvent = 0;
    static readonly TimeSpan StopTimeout = TimeSpan.FromSeconds(60);
    readonly string home;
    readonly object gate = new object();
    Process engine;
    StreamWriter log;
    bool stopping;

    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AllocConsole();
    [DllImport("kernel32.dll")] static extern IntPtr GetConsoleWindow();
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr window, int command);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint processGroup);

    public EngineService(string home)
    {
        this.home = home;
        ServiceName = Program.ServiceName;
        CanStop = true; CanShutdown = true; AutoLog = false;
    }

    protected override void OnStart(string[] args)
    {
        Directory.CreateDirectory(Path.Combine(home, "logs"));
        log = new StreamWriter(Path.Combine(home, "logs", "oieservice.out"), true, new UTF8Encoding(false)) { AutoFlush = true };
        // The engine shares this hidden console, so a console Ctrl+C stops it the way closing a console window does.
        if (GetConsoleWindow() == IntPtr.Zero && !AllocConsole()) throw new Win32Exception(Marshal.GetLastWin32Error());
        ShowWindow(GetConsoleWindow(), 0);
        var start = Program.Bootstrap(home, new[] { "run" });
        start.CreateNoWindow = false;
        start.RedirectStandardOutput = true;
        start.RedirectStandardError = true;
        start.StandardOutputEncoding = Encoding.UTF8;
        start.StandardErrorEncoding = Encoding.UTF8;
        engine = new Process { StartInfo = start, EnableRaisingEvents = true };
        engine.OutputDataReceived += (sender, e) => Write(e.Data);
        engine.ErrorDataReceived += (sender, e) => Write(e.Data);
        engine.Exited += (sender, e) => Exited();
        Write("Starting the engine");
        engine.Start();
        engine.BeginOutputReadLine();
        engine.BeginErrorReadLine();
    }

    protected override void OnStop() { StopEngine(); }
    protected override void OnShutdown() { StopEngine(); }

    void StopEngine()
    {
        lock (gate) { if (stopping) return; stopping = true; }
        if (engine == null || engine.HasExited) return;
        RequestAdditionalTime((int)StopTimeout.TotalMilliseconds + 15000);
        Write("Stopping the engine");
        // Ignore the event in this process, send it to every process on the console, then restore the default.
        SetConsoleCtrlHandler(IntPtr.Zero, true);
        try
        {
            GenerateConsoleCtrlEvent(CtrlCEvent, 0);
            if (!engine.WaitForExit((int)StopTimeout.TotalMilliseconds))
            {
                Write("The engine did not stop within " + StopTimeout.TotalSeconds + " seconds; ending it");
                KillTree(engine.Id);
                engine.WaitForExit(10000);
            }
        }
        finally { SetConsoleCtrlHandler(IntPtr.Zero, false); }
        engine.WaitForExit();
        Write("The engine stopped with exit code " + engine.ExitCode);
    }

    void Exited()
    {
        lock (gate) { if (stopping) return; stopping = true; }
        // The engine ended on its own: report a failure so the Service Control Manager shows the service stopped.
        Write("The engine exited unexpectedly with code " + engine.ExitCode);
        ExitCode = engine.ExitCode == 0 ? 1 : engine.ExitCode;
        new Thread(() => Stop()) { IsBackground = true }.Start();
    }

    static void KillTree(int pid)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "taskkill.exe"), "/PID " + pid + " /T /F") { UseShellExecute = false, CreateNoWindow = true };
        using (var taskkill = Process.Start(start)) taskkill.WaitForExit(30000);
    }

    void Write(string line)
    {
        if (line == null) return;
        lock (log) { log.WriteLine(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss,fff") + " " + line); }
    }
}
