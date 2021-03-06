package datadog.trace.api.writer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.opentracing.SpanFactory
import datadog.trace.common.writer.DDApi
import datadog.trace.common.writer.DDApi.ResponseListener
import org.msgpack.jackson.dataformat.MessagePackFactory
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class DDApiTest extends Specification {
  static mapper = new ObjectMapper(new MessagePackFactory())

  def "sending an empty list of traces returns no errors"() {
    setup:
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
      }
    }
    def client = new DDApi("localhost", agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${agent.address.port}/v0.4/traces"
    client.sendTraces([])

    cleanup:
    agent.close()
  }

  def "non-200 response results in false returned"() {
    setup:
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          response.status(404).send()
        }
      }
    }
    def client = new DDApi("localhost", agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${agent.address.port}/v0.3/traces"
    !client.sendTraces([])

    cleanup:
    agent.close()
  }

  def "content is sent as MSGPACK"() {
    setup:
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          response.send()
        }
      }
    }
    def client = new DDApi("localhost", agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${agent.address.port}/v0.4/traces"
    client.getTraceCounter().addAndGet(traces.size()) >= 0
    client.sendTraces(traces)
    agent.lastRequest.contentType == "application/msgpack"
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "${traces.size()}"
    convertList(agent.lastRequest.body) == expectedRequestBody

    cleanup:
    agent.close()

    // Populate thread info dynamically as it is different when run via gradle vs idea.
    where:
    traces                                                               | expectedRequestBody
    []                                                                   | []
    [SpanFactory.newSpanOf(1L).setTag("service.name", "my-service")]     | [new TreeMap<>([
      "duration" : 0,
      "error"    : 0,
      "meta"     : ["span.type": "fakeType", "thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [:],
      "name"     : "fakeOperation",
      "parent_id": 0,
      "resource" : "fakeResource",
      "service"  : "my-service",
      "span_id"  : 1,
      "start"    : 1000,
      "trace_id" : 1,
      "type"     : "fakeType"
    ])]
    [SpanFactory.newSpanOf(100L).setTag("resource.name", "my-resource")] | [new TreeMap<>([
      "duration" : 0,
      "error"    : 0,
      "meta"     : ["span.type": "fakeType", "thread.name": Thread.currentThread().getName(), "thread.id": "${Thread.currentThread().id}"],
      "metrics"  : [:],
      "name"     : "fakeOperation",
      "parent_id": 0,
      "resource" : "my-resource",
      "service"  : "fakeService",
      "span_id"  : 1,
      "start"    : 100000,
      "trace_id" : 1,
      "type"     : "fakeType"
    ])]
  }

  def "Api ResponseListeners see 200 responses"() {
    setup:
    def agentResponse = new AtomicReference<String>(null)
    ResponseListener responseListener = { String endpoint, JsonNode responseJson ->
      agentResponse.set(responseJson.toString())
    }
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send('{"hello":"test"}')
        }
      }
    }
    def client = new DDApi("localhost", agent.address.port)
    client.addResponseListener(responseListener)
    def traceCounter = client.getTraceCounter()
    traceCounter.set(3)

    when:
    client.sendTraces([])
    then:
    agentResponse.get() == '{"hello":"test"}'
    agent.lastRequest.headers.get("Datadog-Meta-Lang") == "java"
    agent.lastRequest.headers.get("Datadog-Meta-Lang-Version") == System.getProperty("java.version", "unknown")
    agent.lastRequest.headers.get("Datadog-Meta-Tracer-Version") == "Stubbed-Test-Version"
    agent.lastRequest.headers.get("X-Datadog-Trace-Count") == "3" // false data shows the value provided via traceCounter.
    traceCounter.get() == 0

    cleanup:
    agent.close()
  }

  def "Api Downgrades to v3 if v0.4 not available"() {
    setup:
    def v3Agent = httpServer {
      handlers {
        put("v0.3/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
      }
    }
    def client = new DDApi("localhost", v3Agent.address.port)

    expect:
    client.tracesEndpoint == "http://localhost:${v3Agent.address.port}/v0.3/traces"
    client.sendTraces([])

    cleanup:
    v3Agent.close()
  }

  def "Api Downgrades to v3 if timeout exceeded (#delayTrace, #badPort)"() {
    // This test is unfortunately only exercising the read timeout, not the connect timeout.
    setup:
    def agent = httpServer {
      handlers {
        put("v0.3/traces") {
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
        put("v0.4/traces") {
          Thread.sleep(delayTrace)
          def status = request.contentLength > 0 ? 200 : 500
          response.status(status).send()
        }
      }
    }
    def port = badPort ? 999 : agent.address.port
    def client = new DDApi("localhost", port)

    expect:
    client.tracesEndpoint == "http://localhost:${port}/$endpointVersion/traces"

    cleanup:
    agent.close()

    where:
    endpointVersion | delayTrace | badPort
    "v0.4"          | 0          | false
    "v0.3"          | 0          | true
    "v0.4"          | 500        | false
    "v0.3"          | 30000      | false
  }

  static List<TreeMap<String, Object>> convertList(byte[] bytes) {
    return mapper.readValue(bytes, new TypeReference<List<TreeMap<String, Object>>>() {})
  }

  static TreeMap<String, Object> convertMap(byte[] bytes) {
    return mapper.readValue(bytes, new TypeReference<TreeMap<String, Object>>() {})
  }
}
