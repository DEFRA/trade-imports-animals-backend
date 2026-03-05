using Microsoft.AspNetCore.Builder;

namespace TradeImportsAnimalsBackend.Test.Config;

public class EnvironmentTest
{
    [Fact]
    public void IsNotDevModeByDefault()
    {
        var builder = WebApplication.CreateEmptyBuilder(new WebApplicationOptions());
        var isDev = TradeImportsAnimalsBackend.Config.Environment.IsDevMode(builder);
        Assert.False(isDev);
    }
}