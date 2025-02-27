package io.sentry

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.sentry.exception.SentryEnvelopeException
import io.sentry.protocol.Device
import io.sentry.protocol.Request
import io.sentry.protocol.SdkVersion
import io.sentry.protocol.SentryId
import io.sentry.protocol.SentrySpan
import io.sentry.protocol.SentryTransaction
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonSerializerTest {

    private class Fixture {
        val logger: ILogger = mock()
        val serializer: ISerializer
        val hub = mock<IHub>()
        val traceFile = Files.createTempFile("test", "here").toFile()

        init {
            val options = SentryOptions()
            options.dsn = "https://key@sentry.io/proj"
            options.setLogger(logger)
            options.setDebug(true)
            whenever(hub.options).thenReturn(options)
            serializer = JsonSerializer(options)
            options.setEnvelopeReader(EnvelopeReader(serializer))
        }
    }

    private lateinit var fixture: Fixture

    @BeforeTest
    fun before() {
        fixture = Fixture()
    }

    private fun <T> serializeToString(ev: T): String {
        return this.serializeToString { wrt -> fixture.serializer.serialize(ev!!, wrt) }
    }

    private fun serializeToString(serialize: (StringWriter) -> Unit): String {
        val wrt = StringWriter()
        serialize(wrt)
        return wrt.toString()
    }

    private fun serializeToString(envelope: SentryEnvelope): String {
        val outputStream = ByteArrayOutputStream()
        BufferedWriter(OutputStreamWriter(outputStream))
        fixture.serializer.serialize(envelope, outputStream)
        return outputStream.toString()
    }

    @Test
    fun `when serializing SentryEvent-SentryId object, it should become a event_id json without dashes`() {
        val dateIsoFormat = "2000-12-31T23:59:58.000Z"
        val sentryEvent = generateEmptySentryEvent(DateUtils.getDateTime(dateIsoFormat))

        val actual = serializeToString(sentryEvent)
        val expected = "{\"timestamp\":\"$dateIsoFormat\",\"event_id\":\"${sentryEvent.eventId}\",\"contexts\":{}}"

        assertEquals(actual, expected)
    }

    @Test
    fun `when deserializing event_id, it should become a SentryEvent-SentryId uuid`() {
        val expected = UUID.randomUUID().toString().replace("-", "")
        val jsonEvent = "{\"event_id\":\"$expected\"}"

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals(expected, actual!!.eventId.toString())
    }

    @Test
    fun `when serializing SentryEvent-Date, it should become a timestamp json ISO format`() {
        val dateIsoFormat = "2000-12-31T23:59:58.000Z"
        val sentryEvent = generateEmptySentryEvent(DateUtils.getDateTime(dateIsoFormat))
        sentryEvent.eventId = null

        val expected = "{\"timestamp\":\"$dateIsoFormat\",\"contexts\":{}}"

        val actual = serializeToString(sentryEvent)

        assertEquals(expected, actual)
    }

    @Test
    fun `when deserializing timestamp, it should become a SentryEvent-Date`() {
        val dateIsoFormat = "2000-12-31T23:59:58.000Z"
        val expected = DateUtils.getDateTime(dateIsoFormat)

        val jsonEvent = "{\"timestamp\":\"$dateIsoFormat\"}"

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals(expected, actual!!.timestamp)
    }

    @Test
    fun `when deserializing millis timestamp, it should become a SentryEvent-Date`() {
        val dateIsoFormat = "1581410911"
        val expected = DateUtils.getDateTimeWithMillisPrecision(dateIsoFormat)

        val jsonEvent = "{\"timestamp\":\"$dateIsoFormat\"}"

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals(expected, actual!!.timestamp)
    }

    @Test
    fun `when deserializing millis timestamp with mills precision, it should become a SentryEvent-Date`() {
        val dateIsoFormat = "1581410911.988"
        val expected = DateUtils.getDateTimeWithMillisPrecision(dateIsoFormat)

        val jsonEvent = "{\"timestamp\":\"$dateIsoFormat\"}"

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals(expected, actual!!.timestamp)
    }

    @Test
    fun `when deserializing unknown properties, it should be added to unknown field`() {
        val jsonEvent = "{\"string\":\"test\",\"int\":1,\"boolean\":true}"
        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals("test", actual!!.unknown!!["string"] as String)
        assertEquals(1, actual.unknown!!["int"] as Int)
        assertEquals(true, actual.unknown!!["boolean"] as Boolean)
    }

    @Test
    fun `when deserializing unknown properties with nested objects, it should be added to unknown field`() {
        val sentryEvent = generateEmptySentryEvent()
        sentryEvent.eventId = null

        val objects = hashMapOf<String, Any>()
        objects["int"] = 1
        objects["boolean"] = true

        val unknown = hashMapOf<String, Any>()
        unknown["object"] = objects
        sentryEvent.setUnknown(unknown)

        val jsonEvent = "{\"object\":{\"int\":1,\"boolean\":true}}"

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        val hashMapActual = actual!!.unknown!!["object"] as Map<*, *> // gson creates it as JsonObject

        assertEquals(true, hashMapActual.get("boolean") as Boolean)
        assertEquals(1, hashMapActual.get("int") as Int)
    }

    @Test
    fun `when serializing unknown field, its keys should becom part of json`() {
        val dateIsoFormat = "2000-12-31T23:59:58.000Z"
        val sentryEvent = generateEmptySentryEvent(DateUtils.getDateTime(dateIsoFormat))
        sentryEvent.eventId = null

        val objects = hashMapOf<String, Any>()
        objects["int"] = 1
        objects["boolean"] = true

        val unknown = hashMapOf<String, Any>()
        unknown["object"] = objects

        sentryEvent.setUnknown(unknown)

        val actual = serializeToString(sentryEvent)

        val expected = "{\"timestamp\":\"2000-12-31T23:59:58.000Z\",\"contexts\":{},\"object\":{\"boolean\":true,\"int\":1}}"

        assertEquals(actual, expected)
    }

    @Test
    fun `when serializing a TimeZone, it should become a timezone ID string`() {
        val dateIsoFormat = "2000-12-31T23:59:58.000Z"
        val sentryEvent = generateEmptySentryEvent(DateUtils.getDateTime(dateIsoFormat))
        sentryEvent.eventId = null
        val device = Device()
        device.timezone = TimeZone.getTimeZone("Europe/Vienna")
        sentryEvent.contexts.setDevice(device)

        val expected = "{\"timestamp\":\"2000-12-31T23:59:58.000Z\",\"contexts\":{\"device\":{\"timezone\":\"Europe/Vienna\"}}}"
        val actual = serializeToString(sentryEvent)

        assertEquals(actual, expected)
    }

    @Test
    fun `when deserializing a timezone ID string, it should become a Device-TimeZone`() {
        val sentryEvent = generateEmptySentryEvent()
        sentryEvent.eventId = null

        val jsonEvent = "{\"contexts\":{\"device\":{\"timezone\":\"Europe/Vienna\"}}}"

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals("Europe/Vienna", actual!!.contexts.device!!.timezone!!.id)
    }

    @Test
    fun `when serializing a DeviceOrientation, it should become an orientation string`() {
        val dateIsoFormat = "2000-12-31T23:59:58.000Z"
        val sentryEvent = generateEmptySentryEvent(DateUtils.getDateTime(dateIsoFormat))
        sentryEvent.eventId = null
        val device = Device()
        device.orientation = Device.DeviceOrientation.LANDSCAPE
        sentryEvent.contexts.setDevice(device)

        val expected = "{\"timestamp\":\"2000-12-31T23:59:58.000Z\",\"contexts\":{\"device\":{\"orientation\":\"landscape\"}}}"
        val actual = serializeToString(sentryEvent)
        assertEquals(actual, expected)
    }

    @Test
    fun `when deserializing an orientation string, it should become a DeviceOrientation`() {
        val sentryEvent = generateEmptySentryEvent()
        sentryEvent.eventId = null

        val jsonEvent = "{\"contexts\":{\"device\":{\"orientation\":\"landscape\"}}}"

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals(Device.DeviceOrientation.LANDSCAPE, actual!!.contexts.device!!.orientation)
    }

    @Test
    fun `when serializing a SentryLevel, it should become a sentry level string`() {
        val dateIsoFormat = "2000-12-31T23:59:58.000Z"
        val sentryEvent = generateEmptySentryEvent(DateUtils.getDateTime(dateIsoFormat))
        sentryEvent.eventId = null
        sentryEvent.level = SentryLevel.DEBUG

        val expected = "{\"timestamp\":\"2000-12-31T23:59:58.000Z\",\"level\":\"debug\",\"contexts\":{}}"
        val actual = serializeToString(sentryEvent)

        assertEquals(actual, expected)
    }

    @Test
    fun `when deserializing a sentry level string, it should become a SentryLevel`() {
        val sentryEvent = generateEmptySentryEvent()
        sentryEvent.eventId = null

        val jsonEvent = "{\"level\":\"debug\"}"
        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertEquals(SentryLevel.DEBUG, actual!!.level)
    }

    @Test
    fun `when deserializing a event with breadcrumbs containing data, it should become have breadcrumbs`() {
        val jsonEvent = FileFromResources.invoke("event_breadcrumb_data.json")

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)

        assertNotNull(actual) { event ->
            assertNotNull(event.breadcrumbs) {
                assertEquals(2, it.size)
            }
        }
    }

    @Test
    fun `when deserializing a event with custom contexts, they should be set in the event contexts`() {
        val jsonEvent = FileFromResources.invoke("event_with_contexts.json")

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), SentryEvent::class.java)
        val obj = actual!!.contexts["object"] as Map<*, *>
        val number = actual.contexts["number"] as Int
        val list = actual.contexts["list"] as List<*>
        val listObjects = actual.contexts["list_objects"] as List<*>

        assertTrue(obj["boolean"] as Boolean)
        assertEquals("hi", obj["string"] as String)
        assertEquals(9, obj["number"] as Int)

        assertEquals(50, number)

        assertEquals(1, list[0])
        assertEquals(2, list[1])

        val listObjectsFirst = listObjects[0] as Map<*, *>
        assertTrue(listObjectsFirst["boolean"] as Boolean)
        assertEquals("hi", listObjectsFirst["string"] as String)
        assertEquals(9, listObjectsFirst["number"] as Int)

        val listObjectsSecond = listObjects[1] as Map<*, *>
        assertFalse(listObjectsSecond["boolean"] as Boolean)
        assertEquals("ciao", listObjectsSecond["string"] as String)
        assertEquals(10, listObjectsSecond["number"] as Int)
    }

    @Test
    fun `when theres a null value, gson wont blow up`() {
        val json = FileFromResources.invoke("event.json")
        val event = fixture.serializer.deserialize(StringReader(json), SentryEvent::class.java)
        assertNotNull(event)
        assertNull(event.user)
    }

    @Test
    fun `When deserializing a Session all the values should be set to the Session object`() {
        val jsonEvent = FileFromResources.invoke("session.json")

        val actual = fixture.serializer.deserialize(StringReader(jsonEvent), Session::class.java)

        assertSessionData(actual)
    }

    @Test
    fun `When deserializing an Envelope and reader throws IOException it should return null `() {
        val inputStream = mock<InputStream>()
        whenever(inputStream.read(any())).thenThrow(IOException())

        val envelope = fixture.serializer.deserializeEnvelope(inputStream)
        assertNull(envelope)
    }

    @Test
    fun `When serializing a Session all the values should be set to the JSON string`() {
        val session = createSessionMockData()
        val jsonSession = serializeToString(session)
        // reversing, so we can assert values and not a json string
        val expectedSession = fixture.serializer.deserialize(StringReader(jsonSession), Session::class.java)

        assertSessionData(expectedSession)
    }

    @Test
    fun `When deserializing an Envelope, all the values should be set to the SentryEnvelope object`() {
        val jsonEnvelope = FileFromResources.invoke("envelope_session.txt")
        val envelope = fixture.serializer.deserializeEnvelope(ByteArrayInputStream(jsonEnvelope.toByteArray(Charsets.UTF_8)))
        assertEnvelopeData(envelope)
    }

    @Test
    fun `When deserializing an Envelope, SdkVersion should be set`() {
        val jsonEnvelope = FileFromResources.invoke("envelope_session_sdkversion.txt")
        val envelope = fixture.serializer.deserializeEnvelope(ByteArrayInputStream(jsonEnvelope.toByteArray(Charsets.UTF_8)))!!
        assertNotNull(envelope.header.sdkVersion)
        val sdkInfo = envelope.header.sdkVersion!!

        assertEquals("test", sdkInfo.name)
        assertEquals("1.2.3", sdkInfo.version)

        assertNotNull(sdkInfo.integrations)
        assertTrue(sdkInfo.integrations!!.any { it == "NdkIntegration" })

        assertNotNull(sdkInfo.packages)

        assertTrue(
            sdkInfo.packages!!.any {
                it.name == "maven:io.sentry:sentry-android-core"
                it.version == "4.5.6"
            }
        )
    }

    @Test
    fun `When serializing an envelope, all the values should be set`() {
        val session = createSessionMockData()
        val sentryEnvelope = SentryEnvelope.from(fixture.serializer, session, null)

        val jsonEnvelope = serializeToString(sentryEnvelope)
        // reversing it so we can assert the values
        val envelope = fixture.serializer.deserializeEnvelope(ByteArrayInputStream(jsonEnvelope.toByteArray(Charsets.UTF_8)))
        assertEnvelopeData(envelope)
    }

    @Test
    fun `When serializing an envelope, SdkVersion should be set`() {
        val session = createSessionMockData()
        val version = SdkVersion("test", "1.2.3").apply {
            addIntegration("TestIntegration")
            addPackage("abc", "4.5.6")
        }
        val sentryEnvelope = SentryEnvelope.from(fixture.serializer, session, version)

        val jsonEnvelope = serializeToString(sentryEnvelope)
        // reversing it so we can assert the values
        val envelope = fixture.serializer.deserializeEnvelope(ByteArrayInputStream(jsonEnvelope.toByteArray(Charsets.UTF_8)))!!
        assertNotNull(envelope.header.sdkVersion)

        val sdkVersion = envelope.header.sdkVersion!!
        assertEquals(version.name, sdkVersion.name)
        assertEquals(version.version, sdkVersion.version)

        assertNotNull(sdkVersion.integrations)
        assertTrue(sdkVersion.integrations!!.any { it == "TestIntegration" })

        assertNotNull(sdkVersion.packages)
        assertTrue(
            sdkVersion.packages!!.any {
                it.name == "abc"
                it.version == "4.5.6"
            }
        )
    }

    @Test
    fun `when serializing a data map, data should be stringfied`() {
        val data = mapOf("a" to "b")
        val expected = "{\"a\":\"b\"}"

        val dataJson = fixture.serializer.serialize(data)

        assertEquals(expected, dataJson)
    }

    @Test
    fun `serializes trace context`() {
        val traceContext = SentryEnvelopeHeader(null, null, TraceContext(SentryId("3367f5196c494acaae85bbbd535379ac"), "key", "release", "environment", "userId", "segment", "transaction", "0.5"))
        val expected = """{"trace":{"trace_id":"3367f5196c494acaae85bbbd535379ac","public_key":"key","release":"release","environment":"environment","user_id":"userId","user_segment":"segment","transaction":"transaction","sample_rate":"0.5"}}"""
        val json = serializeToString(traceContext)
        assertEquals(expected, json)
    }

    @Test
    fun `serializes trace context with user having null id and segment`() {
        val traceContext = SentryEnvelopeHeader(null, null, TraceContext(SentryId("3367f5196c494acaae85bbbd535379ac"), "key", "release", "environment", null, null, "transaction", "0.6"))
        val expected = """{"trace":{"trace_id":"3367f5196c494acaae85bbbd535379ac","public_key":"key","release":"release","environment":"environment","transaction":"transaction","sample_rate":"0.6"}}"""
        val json = serializeToString(traceContext)
        assertEquals(expected, json)
    }

    @Test
    fun `deserializes trace context`() {
        val json = """{"trace":{"trace_id":"3367f5196c494acaae85bbbd535379ac","public_key":"key","release":"release","environment":"environment","user_id":"userId","user_segment":"segment","transaction":"transaction"}}"""
        val actual = fixture.serializer.deserialize(StringReader(json), SentryEnvelopeHeader::class.java)
        assertNotNull(actual) {
            assertNotNull(it.traceContext) {
                assertEquals(SentryId("3367f5196c494acaae85bbbd535379ac"), it.traceId)
                assertEquals("key", it.publicKey)
                assertEquals("release", it.release)
                assertEquals("environment", it.environment)
                assertEquals("userId", it.userId)
                assertEquals("segment", it.userSegment)
            }
        }
    }

    @Test
    fun `deserializes trace context without user`() {
        val json = """{"trace":{"trace_id":"3367f5196c494acaae85bbbd535379ac","public_key":"key","release":"release","environment":"environment","transaction":"transaction"}}"""
        val actual = fixture.serializer.deserialize(StringReader(json), SentryEnvelopeHeader::class.java)
        assertNotNull(actual) {
            assertNotNull(it.traceContext) {
                assertEquals(SentryId("3367f5196c494acaae85bbbd535379ac"), it.traceId)
                assertEquals("key", it.publicKey)
                assertEquals("release", it.release)
                assertEquals("environment", it.environment)
                assertNull(it.userId)
                assertNull(it.userSegment)
            }
        }
    }

    @Test
    fun `serializes profilingTraceData`() {
        val profilingTraceData = ProfilingTraceData(fixture.traceFile, NoOpTransaction.getInstance())
        profilingTraceData.androidApiLevel = 21
        profilingTraceData.deviceLocale = "deviceLocale"
        profilingTraceData.deviceManufacturer = "deviceManufacturer"
        profilingTraceData.deviceModel = "deviceModel"
        profilingTraceData.deviceOsBuildNumber = "deviceOsBuildNumber"
        profilingTraceData.deviceOsVersion = "11"
        profilingTraceData.isDeviceIsEmulator = true
        profilingTraceData.deviceCpuFrequencies = listOf(1, 2, 3, 4)
        profilingTraceData.devicePhysicalMemoryBytes = "2000000"
        profilingTraceData.buildId = "buildId"
        profilingTraceData.transactionName = "transactionName"
        profilingTraceData.durationNs = "100"
        profilingTraceData.versionName = "versionName"
        profilingTraceData.versionCode = "versionCode"
        profilingTraceData.transactionId = "transactionId"
        profilingTraceData.traceId = "traceId"
        profilingTraceData.profileId = "profileId"
        profilingTraceData.environment = "environment"
        profilingTraceData.sampledProfile = "sampled profile in base 64"

        val stringWriter = StringWriter()
        fixture.serializer.serialize(profilingTraceData, stringWriter)

        val reader = StringReader(stringWriter.toString())
        val objectReader = JsonObjectReader(reader)
        val element = JsonObjectDeserializer().deserialize(objectReader) as Map<*, *>

        assertEquals(21, element["android_api_level"] as Int)
        assertEquals("deviceLocale", element["device_locale"] as String)
        assertEquals("deviceManufacturer", element["device_manufacturer"] as String)
        assertEquals("deviceModel", element["device_model"] as String)
        assertEquals("deviceOsBuildNumber", element["device_os_build_number"] as String)
        assertEquals("android", element["device_os_name"] as String)
        assertEquals("11", element["device_os_version"] as String)
        assertEquals(true, element["device_is_emulator"] as Boolean)
        assertEquals(listOf(1, 2, 3, 4), element["device_cpu_frequencies"] as List<Int>)
        assertEquals("2000000", element["device_physical_memory_bytes"] as String)
        assertEquals("android", element["platform"] as String)
        assertEquals("buildId", element["build_id"] as String)
        assertEquals("transactionName", element["transaction_name"] as String)
        assertEquals("100", element["duration_ns"] as String)
        assertEquals("versionName", element["version_name"] as String)
        assertEquals("versionCode", element["version_code"] as String)
        assertEquals("transactionId", element["transaction_id"] as String)
        assertEquals("traceId", element["trace_id"] as String)
        assertEquals("profileId", element["profile_id"] as String)
        assertEquals("environment", element["environment"] as String)
        assertEquals("sampled profile in base 64", element["sampled_profile"] as String)
    }

    @Test
    fun `deserializes profilingTraceData`() {
        val json = """{
                            "android_api_level":21,
                            "device_locale":"deviceLocale",
                            "device_manufacturer":"deviceManufacturer",
                            "device_model":"deviceModel",
                            "device_os_build_number":"deviceOsBuildNumber",
                            "device_os_name":"android",
                            "device_os_version":"11",
                            "device_is_emulator":true,
                            "device_cpu_frequencies":[1, 2, 3, 4],
                            "device_physical_memory_bytes":"2000000",
                            "platform":"android",
                            "build_id":"buildId",
                            "transaction_name":"transactionName",
                            "duration_ns":"100",
                            "version_name":"versionName",
                            "version_code":"versionCode",
                            "transaction_id":"transactionId",
                            "trace_id":"traceId",
                            "profile_id":"profileId",
                            "environment":"environment",
                            "sampled_profile":"sampled profile in base 64"
                            }"""
        val profilingTraceData = fixture.serializer.deserialize(StringReader(json), ProfilingTraceData::class.java)
        assertNotNull(profilingTraceData)
        assertEquals(21, profilingTraceData.androidApiLevel)
        assertEquals("deviceLocale", profilingTraceData.deviceLocale)
        assertEquals("deviceManufacturer", profilingTraceData.deviceManufacturer)
        assertEquals("deviceModel", profilingTraceData.deviceModel)
        assertEquals("deviceOsBuildNumber", profilingTraceData.deviceOsBuildNumber)
        assertEquals("android", profilingTraceData.deviceOsName)
        assertEquals("11", profilingTraceData.deviceOsVersion)
        assertEquals(true, profilingTraceData.isDeviceIsEmulator)
        assertEquals(listOf(1, 2, 3, 4), profilingTraceData.deviceCpuFrequencies)
        assertEquals("2000000", profilingTraceData.devicePhysicalMemoryBytes)
        assertEquals("android", profilingTraceData.platform)
        assertEquals("buildId", profilingTraceData.buildId)
        assertEquals("transactionName", profilingTraceData.transactionName)
        assertEquals("100", profilingTraceData.durationNs)
        assertEquals("versionName", profilingTraceData.versionName)
        assertEquals("versionCode", profilingTraceData.versionCode)
        assertEquals("transactionId", profilingTraceData.transactionId)
        assertEquals("traceId", profilingTraceData.traceId)
        assertEquals("profileId", profilingTraceData.profileId)
        assertEquals("environment", profilingTraceData.environment)
        assertEquals("sampled profile in base 64", profilingTraceData.sampledProfile)
    }

    @Test
    fun `serializes transaction`() {
        val trace = TransactionContext("transaction-name", "http")
        trace.description = "some request"
        trace.status = SpanStatus.OK
        trace.setTag("myTag", "myValue")
        trace.sampled = true
        val tracer = SentryTracer(trace, fixture.hub)
        tracer.setData("dataKey", "dataValue")
        val span = tracer.startChild("child")
        span.finish(SpanStatus.OK)
        tracer.finish()

        val stringWriter = StringWriter()
        fixture.serializer.serialize(SentryTransaction(tracer), stringWriter)

        val reader = StringReader(stringWriter.toString())
        val objectReader = JsonObjectReader(reader)
        val element = JsonObjectDeserializer().deserialize(objectReader) as Map<*, *>

        assertEquals("transaction-name", element["transaction"] as String)
        assertEquals("transaction", element["type"] as String)
        assertNotNull(element["start_timestamp"] as Number)
        assertNotNull(element["event_id"] as String)
        assertNotNull(element["spans"] as List<*>)
        assertEquals("myValue", (element["tags"] as Map<*, *>)["myTag"] as String)

        assertEquals("dataValue", (element["extra"] as Map<*, *>)["dataKey"] as String)

        val jsonSpan = (element["spans"] as List<*>)[0] as Map<*, *>
        assertNotNull(jsonSpan["trace_id"])
        assertNotNull(jsonSpan["span_id"])
        assertNotNull(jsonSpan["parent_span_id"])
        assertEquals("child", jsonSpan["op"] as String)
        assertNotNull("ok", jsonSpan["status"] as String)
        assertNotNull(jsonSpan["timestamp"])
        assertNotNull(jsonSpan["start_timestamp"])

        val jsonTrace = (element["contexts"] as Map<*, *>)["trace"] as Map<*, *>
        assertNotNull(jsonTrace["trace_id"] as String)
        assertNotNull(jsonTrace["span_id"] as String)
        assertEquals("http", jsonTrace["op"] as String)
        assertEquals("some request", jsonTrace["description"] as String)
        assertEquals("ok", jsonTrace["status"] as String)
    }

    @Test
    fun `deserializes transaction`() {
        val json = """{
                          "transaction": "a-transaction",
                          "type": "transaction",
                          "start_timestamp": 1632395079.503000,
                          "timestamp": 1632395079.807321,
                          "event_id": "3367f5196c494acaae85bbbd535379ac",
                          "contexts": {
                            "trace": {
                              "trace_id": "b156a475de54423d9c1571df97ec7eb6",
                              "span_id": "0a53026963414893",
                              "op": "http",
                              "status": "ok"
                            },
                            "custom": {
                              "some-key": "some-value"
                            }
                          },
                          "extra": {
                            "extraKey": "extraValue"
                          },
                          "spans": [
                            {
                              "start_timestamp": 1632395079.840000,
                              "timestamp": 1632395079.884043,
                              "trace_id": "2b099185293344a5bfdd7ad89ebf9416",
                              "span_id": "5b95c29a5ded4281",
                              "parent_span_id": "a3b2d1d58b344b07",
                              "op": "PersonService.create",
                              "description": "desc",
                              "status": "aborted",
                              "tags": {
                                "name": "value"
                              },
                              "data": {
                                "key": "value"
                              }
                            }
                          ]
                        }"""
        val transaction = fixture.serializer.deserialize(StringReader(json), SentryTransaction::class.java)
        assertNotNull(transaction)
        assertEquals("a-transaction", transaction.transaction)
        assertNotNull(transaction.startTimestamp)
        assertNotNull(transaction.timestamp)
        assertNotNull(transaction.contexts)
        assertNotNull(transaction.contexts.trace)
        assertEquals(SpanStatus.OK, transaction.status)
        assertEquals("transaction", transaction.type)
        assertEquals("b156a475de54423d9c1571df97ec7eb6", transaction.contexts.trace!!.traceId.toString())
        assertEquals("0a53026963414893", transaction.contexts.trace!!.spanId.toString())
        assertEquals("http", transaction.contexts.trace!!.operation)
        assertNotNull(transaction.contexts["custom"])
        assertEquals("some-value", (transaction.contexts["custom"] as Map<*, *>)["some-key"])

        assertEquals("extraValue", transaction.getExtra("extraKey"))

        assertNotNull(transaction.spans)
        assertEquals(1, transaction.spans.size)
        val span = transaction.spans[0]
        assertNotNull(span.startTimestamp)
        assertNotNull(span.timestamp)
        assertNotNull(span.data) {
            assertEquals("value", it["key"])
        }
        assertEquals("2b099185293344a5bfdd7ad89ebf9416", span.traceId.toString())
        assertEquals("5b95c29a5ded4281", span.spanId.toString())
        assertEquals("a3b2d1d58b344b07", span.parentSpanId.toString())
        assertEquals("PersonService.create", span.op)
        assertEquals(SpanStatus.ABORTED, span.status)
        assertEquals("desc", span.description)
        assertEquals(mapOf("name" to "value"), span.tags)
    }

    @Test
    fun `deserializes legacy timestamp format in spans and transactions`() {
        val json = """{
                          "transaction": "a-transaction",
                          "type": "transaction",
                          "start_timestamp": "2020-10-23T10:24:01.791Z",
                          "timestamp": "2020-10-23T10:24:02.791Z",
                          "event_id": "3367f5196c494acaae85bbbd535379ac",
                          "contexts": {
                            "trace": {
                              "trace_id": "b156a475de54423d9c1571df97ec7eb6",
                              "span_id": "0a53026963414893",
                              "op": "http",
                              "status": "ok"
                            }
                          },
                          "spans": [
                            {
                              "start_timestamp": "2021-03-05T08:51:12.838Z",
                              "timestamp": "2021-03-05T08:51:12.949Z",
                              "trace_id": "2b099185293344a5bfdd7ad89ebf9416",
                              "span_id": "5b95c29a5ded4281",
                              "parent_span_id": "a3b2d1d58b344b07",
                              "op": "PersonService.create",
                              "description": "desc",
                              "status": "aborted"
                            }
                          ]
                        }"""
        val transaction = fixture.serializer.deserialize(StringReader(json), SentryTransaction::class.java)
        assertNotNull(transaction) {
            assertNotNull(it.startTimestamp)
            assertNotNull(it.timestamp)
        }
    }

    @Test
    fun `serializes span data`() {
        val sentrySpan = SentrySpan(createSpan() as Span, mapOf("data1" to "value1"))

        val serialized = serializeToString(sentrySpan)
        val deserialized = fixture.serializer.deserialize(StringReader(serialized), SentrySpan::class.java)

        assertNotNull(deserialized?.data) {
            assertNotNull(it["data1"]) {
                assertEquals("value1", it)
            }
        }
    }

    @Test
    fun `serializing user feedback`() {
        val actual = serializeToString(userFeedback)

        val expected = "{\"event_id\":\"${userFeedback.eventId}\",\"name\":\"${userFeedback.name}\"," +
            "\"email\":\"${userFeedback.email}\",\"comments\":\"${userFeedback.comments}\"}"

        assertEquals(expected, actual)
    }

    @Test
    fun `deserializing user feedback`() {
        val jsonUserFeedback = "{\"event_id\":\"c2fb8fee2e2b49758bcb67cda0f713c7\"," +
            "\"name\":\"John\",\"email\":\"john@me.com\",\"comments\":\"comment\"}"
        val actual = fixture.serializer.deserialize(StringReader(jsonUserFeedback), UserFeedback::class.java)
        assertNotNull(actual)
        assertEquals(userFeedback.eventId, actual.eventId)
        assertEquals(userFeedback.name, actual.name)
        assertEquals(userFeedback.email, actual.email)
        assertEquals(userFeedback.comments, actual.comments)
    }

    @Test
    fun `serialize envelope with item throwing`() {
        val eventID = SentryId()
        val header = SentryEnvelopeHeader(eventID)

        val message = "hello"
        val attachment = Attachment(message.toByteArray(), "bytes.txt")
        val validAttachmentItem = SentryEnvelopeItem.fromAttachment(attachment, 5)

        val invalidAttachmentItem = SentryEnvelopeItem.fromAttachment(Attachment("no"), 5)
        val envelope = SentryEnvelope(header, listOf(invalidAttachmentItem, validAttachmentItem))

        val actualJson = serializeToString(envelope)

        val expectedJson = "{\"event_id\":\"${eventID}\"}\n" +
            "{\"filename\":\"${attachment.filename}\"," +
            "\"type\":\"attachment\"," +
            "\"attachment_type\":\"event.attachment\"," +
            "\"length\":${attachment.bytes?.size}}\n" +
            "$message\n"

        assertEquals(expectedJson, actualJson)

        verify(fixture.logger)
            .log(
                eq(SentryLevel.ERROR),
                eq("Failed to create envelope item. Dropping it."),
                any<SentryEnvelopeException>()
            )
    }

    @Test
    fun `empty maps are serialized to null`() {
        val event = SentryEvent()
        event.tags = emptyMap()

        val serialized = serializeToString(event)
        val deserialized = fixture.serializer.deserialize(StringReader(serialized), SentryEvent::class.java)

        assertNull(deserialized?.tags)
    }

    @Test
    fun `empty lists are serialized to null`() {
        val event = generateEmptySentryEvent()
        event.threads = listOf()

        val serialized = serializeToString(event)
        val deserialized = fixture.serializer.deserialize(StringReader(serialized), SentryEvent::class.java)

        assertNull(deserialized?.threads)
    }

    @Test
    fun `Long can be serialized inside request data`() {
        val request = Request()

        data class LongContainer(val longValue: Long)

        request.data = LongContainer(10)

        val serialized = serializeToString(request)
        val deserialized = fixture.serializer.deserialize(StringReader(serialized), Request::class.java)

        val deserializedData = deserialized?.data as? Map<String, Any>
        assertNotNull(deserializedData)
        assertEquals(10, deserializedData["longValue"])
    }

    @Test
    fun `Primitives can be serialized inside request data`() {
        val request = Request()

        request.data = JsonReflectionObjectSerializerTest.ClassWithPrimitiveFields(
            17,
            3,
            'x',
            9001,
            0.9f,
            0.99,
            true
        )

        val serialized = serializeToString(request)
        val deserialized = fixture.serializer.deserialize(StringReader(serialized), Request::class.java)

        val deserializedData = deserialized?.data as? Map<String, Any>
        assertNotNull(deserializedData)
        assertEquals(17, deserializedData["byte"])
        assertEquals(3, deserializedData["short"])
        assertEquals("x", deserializedData["char"])
        assertEquals(9001, deserializedData["integer"])
        assertEquals(0.9, deserializedData["float"])
        assertEquals(0.99, deserializedData["double"])
        assertEquals(true, deserializedData["boolean"])
    }

    @Test
    fun `json serializer uses logger set on SentryOptions`() {
        val logger = mock<ILogger>()
        val options = SentryOptions()
        options.setLogger(logger)
        options.setDebug(true)
        whenever(logger.isEnabled(any())).thenReturn(true)

        (options.serializer as JsonSerializer).serialize(mapOf("key" to "val"), mock())
        verify(logger).log(
            any(),
            check {
                assertTrue(it.startsWith("Serializing object:"))
            },
            any<Any>()
        )
    }

    @Test
    fun `json serializer does not close the stream that is passed in`() {
        val stream = mock<OutputStream>()
        JsonSerializer(SentryOptions()).serialize(SentryEnvelope.from(fixture.serializer, SentryEvent(), null), stream)

        verify(stream, never()).close()
    }

    private fun assertSessionData(expectedSession: Session?) {
        assertNotNull(expectedSession)
        assertEquals(UUID.fromString("c81d4e2e-bcf2-11e6-869b-7df92533d2db"), expectedSession.sessionId)
        assertEquals("123", expectedSession.distinctId)
        assertTrue(expectedSession.init!!)
        assertEquals("2020-02-07T14:16:00.000Z", DateUtils.getTimestamp(expectedSession.started!!))
        assertEquals("2020-02-07T14:16:00.000Z", DateUtils.getTimestamp(expectedSession.timestamp!!))
        assertEquals(6000.toDouble(), expectedSession.duration)
        assertEquals(Session.State.Ok, expectedSession.status)
        assertEquals(2, expectedSession.errorCount())
        assertEquals(123456.toLong(), expectedSession.sequence)
        assertEquals("io.sentry@1.0+123", expectedSession.release)
        assertEquals("debug", expectedSession.environment)
        assertEquals("127.0.0.1", expectedSession.ipAddress)
        assertEquals("jamesBond", expectedSession.userAgent)
    }

    private fun assertEnvelopeData(expectedEnveope: SentryEnvelope?) {
        assertNotNull(expectedEnveope)
        assertEquals(1, expectedEnveope.items.count())
        expectedEnveope.items.forEach {
            assertEquals(SentryItemType.Session, it.header.type)
            val reader =
                InputStreamReader(ByteArrayInputStream(it.data), Charsets.UTF_8)
            val actualSession = fixture.serializer.deserialize(reader, Session::class.java)
            assertSessionData(actualSession)
        }
    }

    private fun generateEmptySentryEvent(date: Date = Date()): SentryEvent =
        SentryEvent(date)

    private fun createSessionMockData(): Session =
        Session(
            Session.State.Ok,
            DateUtils.getDateTime("2020-02-07T14:16:00.000Z"),
            DateUtils.getDateTime("2020-02-07T14:16:00.000Z"),
            2,
            "123",
            UUID.fromString("c81d4e2e-bcf2-11e6-869b-7df92533d2db"),
            true,
            123456.toLong(),
            6000.toDouble(),
            "127.0.0.1",
            "jamesBond",
            "debug",
            "io.sentry@1.0+123"
        )

    private val userFeedback: UserFeedback get() {
        val eventId = SentryId("c2fb8fee2e2b49758bcb67cda0f713c7")
        return UserFeedback(eventId).apply {
            name = "John"
            email = "john@me.com"
            comments = "comment"
        }
    }

    private fun createSpan(): ISpan {
        val trace = TransactionContext("transaction-name", "http").apply {
            description = "some request"
            status = SpanStatus.OK
            setTag("myTag", "myValue")
        }
        val tracer = SentryTracer(trace, fixture.hub)
        val span = tracer.startChild("child")
        span.finish(SpanStatus.OK)
        tracer.finish()
        return span
    }
}
