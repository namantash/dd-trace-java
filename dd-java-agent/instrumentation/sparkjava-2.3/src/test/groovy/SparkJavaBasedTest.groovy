import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.TestUtils
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import okhttp3.OkHttpClient
import okhttp3.Request
import spark.Spark
import spark.embeddedserver.jetty.JettyHandler
import spock.lang.Shared

class SparkJavaBasedTest extends AgentTestRunner {

  static {
    System.setProperty("dd.integration.jetty.enabled", "true")
    System.setProperty("dd.integration.sparkjava.enabled", "true")
  }

  @Shared
  int port

  OkHttpClient client = OkHttpUtils.client()

  def setupSpec() {
    port = TestUtils.randomOpenPort()
    TestSparkJavaApplication.initSpark(port)
  }

  def cleanupSpec() {
    Spark.stop()
  }

  def "valid response"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    port != 0
    response.body().string() == "Hello World"
  }

  def "valid response with registered trace"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    port != 0
    response.body().string() == "Hello World"

    and:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
  }


  def "generates spans"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$port/param/asdf1234")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string() == "Hello asdf1234"
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1

    def trace = TEST_WRITER.firstTrace()
    trace.size() == 1
    def context = trace[0].context()
    context.serviceName == "unnamed-java-app"
    context.operationName == "jetty.request"
    context.resourceName == "GET /param/:param"
    context.spanType == DDSpanTypes.HTTP_SERVER
    !context.getErrorFlag()
    context.parentId == "0"
    def tags = context.tags
    tags["http.url"] == "http://localhost:$port/param/asdf1234"
    tags["http.method"] == "GET"
    tags["span.kind"] == "server"
    tags["span.type"] == DDSpanTypes.HTTP_SERVER
    tags["component"] == "jetty-handler"
    tags["http.status_code"] == 200
    tags["thread.name"] != null
    tags["thread.id"] != null
    tags[Config.RUNTIME_ID_TAG] == Config.get().runtimeId
    tags["span.origin.type"] == JettyHandler.name
    tags.size() == 10
  }

}
