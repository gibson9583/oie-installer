using System.ServiceProcess;
using System.Threading;

// A real SCM service with a deliberate STOP_PENDING interval; no engine or database.
internal sealed class WindowsServiceFixture : ServiceBase
{
    private WindowsServiceFixture()
    {
        ServiceName = "Open Integration Engine Service";
        CanStop = true;
        AutoLog = false;
    }

    protected override void OnStart(string[] args) { }

    protected override void OnStop()
    {
        RequestAdditionalTime(20000);
        Thread.Sleep(10000);
    }

    private static void Main()
    {
        ServiceBase.Run(new WindowsServiceFixture());
    }
}
