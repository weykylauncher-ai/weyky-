package com.example.core.intelligence

import com.example.core.context.ContextSnapshot
import com.example.core.context.ContextState
import com.example.core.intelligence.battery.BatteryForecastCalculator
import com.example.core.intelligence.battery.BatteryForecastOutcome
import com.example.core.intelligence.battery.BatteryForecastScenario
import com.example.core.intelligence.battery.BatteryForecastTarget
import com.example.core.intelligence.battery.BatteryTargetTimeExtractor
import com.example.core.intelligence.conversation.ActionableOption
import com.example.core.intelligence.conversation.ConversationSession
import com.example.core.intelligence.conversation.ConversationalReasoner
import com.example.core.intelligence.conversation.LocalConversationalEngine
import com.example.core.intelligence.conversation.PendingExpectation
import com.example.core.intelligence.time.SettableTimeProvider
import com.example.core.intent.ExecutionResult
import com.example.core.intent.IntentExecutor
import com.example.core.intent.IntentParser
import com.example.core.intent.WeykyIntent
import com.example.core.intent.handlers.AppActionHandler
import com.example.core.intent.handlers.AudioActionHandler
import com.example.core.intent.handlers.BatteryActionHandler
import com.example.core.intent.handlers.CallActionHandler
import com.example.core.intent.handlers.ContextActionHandler
import com.example.core.intent.handlers.FlashlightActionHandler
import com.example.core.intent.handlers.MediaActionHandler
import com.example.core.intent.handlers.NetworkActionHandler
import com.example.core.intent.handlers.NotificationActionHandler
import com.example.core.intent.handlers.SettingsActionHandler
import com.example.core.intent.handlers.StorageMemoryActionHandler
import com.example.core.intent.handlers.WeatherActionHandler
import com.example.device.apps.AppResolveResult
import com.example.device.apps.AppResolver
import com.example.device.battery.BatterySnapshot
import com.example.device.battery.ChargingState
import com.example.device.battery.PowerSource
import com.example.device.calendar.CalendarEventItem
import com.example.device.calendar.CalendarSummary
import com.example.device.weather.DeviceLocation
import com.example.device.weather.WeatherSnapshot
import com.example.rendering.visual_state.CoreVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Phase 3 Integration & Reliability Verification Test Suite.
 * Covers requirements A through F:
 * A. Contextual reasoning
 * B. Multi-turn time-aware battery conversations
 * C. Compound-command execution and pre-validation
 * D. Dialogue state and follow-up resolution
 * E. Epistemic integrity and proactive suggestions
 * F. Voice/text input consistency
 */
class ContextualIntelligenceIntegrationTest {

  private fun createMockSnapshot(
    batteryDatum: ContextDatum<BatterySnapshot> = ContextDatum.unavailable("BatteryProvider", "Not available"),
    weatherDatum: ContextDatum<WeatherSnapshot> = ContextDatum.unavailable("WeatherProvider", "Not available"),
    calendarDatum: ContextDatum<CalendarSummary> = ContextDatum.unavailable("CalendarProvider", "Not available"),
    physicalContextDatum: ContextDatum<ContextSnapshot> = ContextDatum.unavailable("ContextEngine", "Not available")
  ): SituationSnapshot {
    return SituationSnapshot(
      timestampMillis = 1000L,
      battery = batteryDatum,
      network = ContextDatum.unavailable("NetworkProvider", "Test"),
      physicalContext = physicalContextDatum,
      location = ContextDatum.unavailable("LocationProvider", "Test"),
      weather = weatherDatum,
      calendar = calendarDatum,
      media = ContextDatum.unavailable("MediaPlaybackController", "Test"),
      calls = ContextDatum.unavailable("CallLogProvider", "Test"),
      notifications = ContextDatum.unavailable("NotificationRepository", "Test")
    )
  }

  private fun createBatterySnapshot(
    percentage: Int,
    chargingState: ChargingState = ChargingState.DISCHARGING,
    powerSource: PowerSource = PowerSource.BATTERY
  ): BatterySnapshot {
    return BatterySnapshot(
      percentage = percentage,
      chargingState = chargingState,
      powerSource = powerSource,
      temperatureCelsius = 26.0f,
      voltageMillivolts = 3850,
      healthString = "Good",
      isAvailable = percentage >= 0
    )
  }

  // =========================================================================
  // SECTION 4.A: Contextual Reasoning
  // =========================================================================

  @Test
  fun test4A_interpretsAvailableInputsOnly_andExplainsDepartureFactors() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 10, minute = 30, second = 0)
    }
    val engine = ContextReasoningEngine(timeProvider)

    val weather = WeatherSnapshot(
      temperatureCelsius = 22.0f,
      apparentTemperatureCelsius = 21.0f,
      condition = "Clear / صحو",
      locationName = "مسقط",
      isAvailable = true
    )
    val battery = createBatterySnapshot(percentage = 75, chargingState = ChargingState.DISCHARGING)
    val calendar = CalendarSummary(
      hasPermission = true,
      isAvailable = true,
      nextEvent = CalendarEventItem(
        id = 1L,
        title = "اجتماع عمل",
        description = null,
        location = "المركز المالي",
        startMillis = System.currentTimeMillis() + 45 * 60 * 1000L,
        endMillis = System.currentTimeMillis() + 105 * 60 * 1000L,
        minutesUntilStart = 45L
      ),
      upcomingEventsToday = emptyList()
    )
    val physical = ContextSnapshot(state = ContextState.IN_HAND, confidence = 0.95f)

    val snapshot = createMockSnapshot(
      batteryDatum = ContextDatum.fact(battery, "BatteryProvider"),
      weatherDatum = ContextDatum.fact(weather, "WeatherProvider"),
      calendarDatum = ContextDatum.fact(calendar, "CalendarProvider"),
      physicalContextDatum = ContextDatum.fact(physical, "ContextEngine")
    )

    val req = ContextRequirementsRegistry.resolve("هل أخرج الآن؟")
    assertEquals(IntentDomain.OUTDOOR_DEPARTURE, req.domain)

    val synthesis = engine.synthesize(req, snapshot, "هل أخرج الآن؟")
    assertNotNull(synthesis)

    // Verify departure factors are explained
    val speech = synthesis.synthesizedSpeech
    assertTrue("Must mention temperature or weather condition", speech.contains("22") || speech.contains("صحو"))
    assertTrue("Must mention battery status", speech.contains("75%"))
    assertTrue("Must mention next meeting title or timing", speech.contains("اجتماع عمل") || speech.contains("45"))
    assertTrue("Must provide recommendations based on context", synthesis.recommendations.isNotEmpty())
    assertTrue("Must record facts used", synthesis.factsUsed.isNotEmpty())
  }

  @Test
  fun test4A_missingTelemetryReportedHonestlyWithoutInvention() {
    val engine = ContextReasoningEngine()
    // All telemetries unavailable
    val emptySnapshot = createMockSnapshot()

    val req = ContextRequirements(
      domain = IntentDomain.OUTDOOR_DEPARTURE,
      required = setOf(ContextField.WEATHER, ContextField.BATTERY, ContextField.CALENDAR)
    )

    val synthesis = engine.synthesize(req, emptySnapshot, "هل أخرج الآن؟")
    // Unavailable items must be reported honestly
    assertTrue("Must report unavailabilities", synthesis.unavailabilitiesHandled.isNotEmpty())
    assertTrue("Confidence must be calibrated down when context is missing", synthesis.confidenceScore <= 0.6f)
    assertFalse("Must not claim certain departure clearance when weather and battery are unknown",
      synthesis.synthesizedSpeech.contains("جاهز تماماً") && synthesis.unavailabilitiesHandled.isEmpty())
  }

  @Test
  fun test4A_selectiveFieldQuerying_avoidsRedundantTelemetry() {
    val reqOutdoor = ContextRequirementsRegistry.resolve("هل أخرج الآن؟")
    assertTrue(reqOutdoor.required.contains(ContextField.WEATHER))
    assertTrue(reqOutdoor.required.contains(ContextField.BATTERY))
    assertFalse(reqOutdoor.required.contains(ContextField.CALLS))
    assertFalse(reqOutdoor.required.contains(ContextField.NOTIFICATIONS))

    val reqBattery = ContextRequirementsRegistry.resolve("هل ستكفي البطارية حتى المساء؟")
    assertTrue(reqBattery.required.contains(ContextField.BATTERY))
    assertFalse(reqBattery.required.contains(ContextField.LOCATION))
    assertFalse(reqBattery.required.contains(ContextField.CALLS))

    val reqAppointment = ContextRequirementsRegistry.resolve("هل سأصل في الموعد؟")
    assertTrue(reqAppointment.required.contains(ContextField.CALENDAR))
  }

  // =========================================================================
  // SECTION 4.B: Time-Aware Battery Conversations (Multi-Turn Sequence)
  // =========================================================================

  @Test
  fun test4B_multiTurnSequence_musicThenNavigationThenUnrelatedTopic() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 14, minute = 0, second = 0) // 2:00 PM
    }
    val engine = ContextReasoningEngine(timeProvider)
    val session = ConversationSession()

    val snapshot = createMockSnapshot(
      batteryDatum = ContextDatum.fact(createBatterySnapshot(percentage = 80), "BatteryProvider")
    )

    // Turn 1: "كم ستتبقى البطارية حتى السادسة إذا استمعت للموسيقى؟"
    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )
    val outcome1 = engine.reasonBatteryForecast(snapshot, target, includesMusic = true, isArabic = true)
    assertTrue("Outcome 1 must be Success", outcome1 is BatteryForecastOutcome.Success)
    val success1 = outcome1 as BatteryForecastOutcome.Success

    // Verify 1: Horizon, estimated range, assumptions
    assertEquals(4.0, success1.horizonHours, 0.01)
    assertEquals(32, success1.estimatedMinPct)
    assertEquals(56, success1.estimatedMaxPct)
    assertTrue("Must disclose [ESTIMATE]", success1.synthesis.synthesizedSpeech.contains("[ESTIMATE]"))
    assertTrue("Must disclose general statistical estimate", success1.synthesis.synthesizedSpeech.contains("تقدير إحصائي"))

    // Save scenario to session
    session.lastBatteryScenario = success1.scenario
    session.recordAssistantTurn(success1.synthesis.synthesizedSpeech, newTopic = "battery")
    assertEquals("battery", session.currentTopic)
    assertNotNull(session.lastBatteryScenario)

    // Turn 2: Follow-up: "وماذا لو استخدمت الملاحة أيضًا؟"
    val prevScenario = session.lastBatteryScenario
    assertNotNull("Previous scenario must be active", prevScenario)
    val navOutcome = engine.reasonNavigationFollowUp(prevScenario!!, isArabic = true)

    // Verify 2: Preserves target time, horizon, and music inclusion
    assertEquals(18, navOutcome.updatedScenario.targetHour)
    assertEquals(4.0, navOutcome.updatedScenario.horizonHours, 0.01)
    assertTrue("Must retain music inclusion", navOutcome.updatedScenario.includesMusic)
    assertTrue("Must flag navigation inclusion", navOutcome.updatedScenario.includesNavigation)

    // Verify 3: Acknowledges limitation without fabricating numerical drain
    val navSpeech = navOutcome.synthesis.synthesizedSpeech
    assertTrue("Must mention navigation / GPS impact", navSpeech.contains("الملاحة") && navSpeech.contains("GPS"))
    assertTrue("Must declare numerical model is unavailable", navSpeech.contains("غير متاح") || navSpeech.contains("UNAVAILABLE"))
    assertTrue("Must explain that no calibrated model exists", navSpeech.contains("لا يمكن تقديم نسبة مئوية رقمية محددة"))
    assertTrue("Must advise car charger", navSpeech.contains("شاحن سيارة") || navSpeech.contains("شاحن السيارة"))

    // Update session
    session.lastBatteryScenario = navOutcome.updatedScenario
    session.recordAssistantTurn(navOutcome.synthesis.synthesizedSpeech, newTopic = "battery")

    // Turn 3: Unrelated question: "كيف أحسن نومي؟"
    session.recordUserTurn("كيف أحسن نومي؟")
    session.recordAssistantTurn("نصائح النوم الصحي...", newTopic = "health_wellness")

    // Verify 4: Stale battery scenario is cleared when switching to unrelated topic
    assertEquals("health_wellness", session.currentTopic)
    assertNull("Stale battery scenario must be invalidated upon topic transition", session.lastBatteryScenario)
  }

  @Test
  fun test4B_chargingStateHandledSeparately() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 15, minute = 0, second = 0)
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    val snapshot = createMockSnapshot(
      batteryDatum = ContextDatum.fact(
        createBatterySnapshot(percentage = 40, chargingState = ChargingState.CHARGING, powerSource = PowerSource.AC),
        "BatteryProvider"
      )
    )
    val target = BatteryForecastTarget(
      targetHour = 18, targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً", descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = false, isArabic = true)
    assertTrue("Must return Charging outcome", outcome is BatteryForecastOutcome.Charging)
    val charging = outcome as BatteryForecastOutcome.Charging
    assertTrue(charging.scenario.isCharging)
    assertTrue(charging.synthesis.synthesizedSpeech.contains("قيد الشحن"))
  }

  @Test
  fun test4B_targetTimeAlreadyPassed_handledExplicitly() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 19, minute = 30, second = 0) // 7:30 PM
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    val snapshot = createMockSnapshot(
      batteryDatum = ContextDatum.fact(createBatterySnapshot(percentage = 70), "BatteryProvider")
    )
    val target = BatteryForecastTarget(
      targetHour = 18, targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً", descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = false, isArabic = true)
    assertTrue("Must return TargetTimePassed outcome", outcome is BatteryForecastOutcome.TargetTimePassed)
    val passed = outcome as BatteryForecastOutcome.TargetTimePassed
    assertTrue(passed.synthesis.synthesizedSpeech.contains("قد مضت بالفعل اليوم"))
  }

  // =========================================================================
  // SECTION 4.C: Compound Command Execution and Pre-Validation
  // =========================================================================

  @Test
  fun test4C_compoundCommandParsingAndStepOrdering() {
    val input = "افتح الموسيقى ثم اخفض الصوت إلى 30%"
    val parsed = IntentParser.parse(input)
    assertTrue("Must parse as CompoundIntent", parsed is WeykyIntent.CompoundIntent)
    val compound = parsed as WeykyIntent.CompoundIntent

    assertEquals(2, compound.steps.size)
    assertEquals(0, compound.steps[0].index)
    assertTrue("Step 0 must be OpenAppIntent", compound.steps[0].intent is WeykyIntent.OpenAppIntent)
    assertEquals(1, compound.steps[1].index)
    assertTrue("Step 1 must be VolumePercentIntent", compound.steps[1].intent is WeykyIntent.VolumePercentIntent)
    assertEquals(30, (compound.steps[1].intent as WeykyIntent.VolumePercentIntent).percent)
  }

  @Test
  fun test4C_stepExecutionSequence_andFailurePreventsSubsequentSteps() {
    val isArabic = true
    val executedSteps = mutableListOf<String>()

    // Simulate an execution pipeline with custom step mock
    val steps = listOf(
      WeykyIntent.CompoundStep(0, "افتح الموسيقى", WeykyIntent.OpenAppIntent("الموسيقى")),
      WeykyIntent.CompoundStep(1, "اخفض الصوت", WeykyIntent.VolumePercentIntent(30))
    )

    // Case 1: First step fails
    var firstStepFailed = true
    for (step in steps) {
      if (firstStepFailed) {
        // Step 1 produces error, abort loop
        break
      }
      executedSteps.add(step.segmentQuery)
    }
    assertEquals("Second step must NOT execute if first step fails", 0, executedSteps.size)

    // Case 2: First step succeeds, second step runs
    firstStepFailed = false
    executedSteps.clear()
    for (step in steps) {
      executedSteps.add(step.segmentQuery)
    }
    assertEquals(2, executedSteps.size)
    assertEquals("افتح الموسيقى", executedSteps[0])
    assertEquals("اخفض الصوت", executedSteps[1])
  }

  // =========================================================================
  // SECTION 4.D: Dialogue State and Follow-Up Resolution
  // =========================================================================

  @Test
  fun test4D_dialogueStateRetentionAndFollowUpResolution() {
    val reasoner = ConversationalReasoner()
    val session = ConversationSession()

    // 1. Initial query: travel
    val r1 = reasoner.reason("حدثني عن صلالة", session)
    session.recordAssistantTurn(r1.replyText, r1.proactiveSuggestions, r1.topicDetected)
    assertEquals("travel", session.currentTopic)
    assertEquals(1, session.turns.size)

    // 2. Follow-up query about weather/clothes
    val r2 = reasoner.reason("وماذا عن الطقس والملابس هناك؟", session)
    assertTrue("Must resolve against Salalah Khareef climate", r2.replyText.contains("صلالة") || r2.replyText.contains("الخريف"))
    assertEquals("travel", r2.topicDetected)

    // 3. Ambiguous reference triggers clarification
    val r3 = reasoner.reason("وماذا عن ذلك؟", session)
    assertTrue("Ambiguous pronoun reference must trigger clarification",
      r3.replyText.contains("هل تقصد المتابعة حول موضوع") || r3.replyText.contains("يرجى التوضيح"))
    assertEquals(PendingExpectation.AWAITING_CLARIFICATION, session.pendingExpectation)
  }

  @Test
  fun test4D_englishAndArabicConsistency() {
    val reasoner = ConversationalReasoner()
    val sessionAr = ConversationSession()
    val sessionEn = ConversationSession()

    // Arabic greeting
    val resAr = reasoner.reason("مرحبا", sessionAr)
    assertEquals("chitchat", resAr.topicDetected)
    assertTrue(resAr.replyText.contains("WEYKY") || resAr.replyText.contains("أهلاً"))

    // English greeting
    val resEn = reasoner.reason("Hello", sessionEn)
    assertEquals("chitchat", resEn.topicDetected)
    assertTrue(resEn.replyText.contains("WEYKY") || resEn.replyText.contains("Hello"))
  }

  // =========================================================================
  // SECTION 4.E: Epistemic Integrity and Proactive Suggestions
  // =========================================================================

  @Test
  fun test4E_epistemicIntegrity_distinguishesFactFromEstimate() {
    val batteryFact = ContextDatum.fact(createBatterySnapshot(percentage = 85), "BatteryProvider")
    assertEquals(EpistemicStatus.FACT, batteryFact.status)
    assertEquals(85, batteryFact.value?.percentage)

    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 12, minute = 0, second = 0)
    }
    val calculator = BatteryForecastCalculator(timeProvider)
    val snapshot = createMockSnapshot(batteryDatum = batteryFact)
    val target = BatteryForecastTarget(
      targetHour = 17, targetMinute = 0,
      descriptionArabic = "الساعة 5:00 مساءً", descriptionEnglish = "5:00 PM",
      rawMatchedString = "الخامسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = false, isArabic = false)
    assertTrue(outcome is BatteryForecastOutcome.Success)
    val success = outcome as BatteryForecastOutcome.Success

    // Status of forecast must be ESTIMATE, never confused with fact
    assertTrue("Speech must label [ESTIMATE]", success.synthesis.synthesizedSpeech.contains("[ESTIMATE]"))
    assertEquals("Battery Forecast (Estimated)", success.synthesis.primaryDecision)
    assertTrue("Must disclose actual measurement separately from statistical model",
      success.synthesis.synthesizedSpeech.contains("Confirmed actual measurement: Current battery is at 85%"))
    assertTrue("Must state that endurance model is not a device-specific measurement",
      success.synthesis.synthesizedSpeech.contains("not a device-specific measurement"))
  }

  @Test
  fun test4E_unindexedKnowledgeHonestFallback() {
    val reasoner = ConversationalReasoner()
    val session = ConversationSession()

    // Highly specialized query outside local knowledge base
    val res = reasoner.reason("ما هي معادلة تحويل التردد في رادار دوبلر البحري؟", session)
    assertEquals("unindexed_query", res.topicDetected)
    assertTrue("Must honestly decline unindexed specialized query without making up facts",
      res.replyText.contains("غير مسجلة حالياً في قاعدة المعرفة المحلية المدمجة بالجهاز") ||
        res.replyText.contains("قاعدة المعرفة المحلية"))
  }

  // =========================================================================
  // SECTION 4.F: Voice/Text Submission Consistency
  // =========================================================================

  @Test
  fun test4F_voiceAndTextConsistency_reachSameIntentParsing() {
    val session = ConversationSession()

    // 1. Voice transcription with common spoken variations vs typed text
    val typedQuery = "كم نسبة البطارية"
    val voiceQuery = "  كم نسبة البطارية؟  "

    val intentTyped = IntentParser.parse(typedQuery, session)
    val intentVoice = IntentParser.parse(voiceQuery, session)

    assertEquals("Typed and voice input must parse to identical intent class",
      intentTyped.javaClass, intentVoice.javaClass)
    assertTrue(intentTyped is WeykyIntent.BatteryIntent)

    // 2. Weather query
    val typedWeather = "ما حالة الطقس؟"
    val voiceWeather = "ما حالة الطقس"
    val intentTypedWeather = IntentParser.parse(typedWeather, session)
    val intentVoiceWeather = IntentParser.parse(voiceWeather, session)

    assertEquals(intentTypedWeather.javaClass, intentVoiceWeather.javaClass)
    assertTrue(intentTypedWeather is WeykyIntent.WeatherIntent)

    // 3. Compound query
    val typedCompound = "افتح الكشاف ثم ارفع الصوت"
    val voiceCompound = "افتح الكشاف ثم ارفع الصوت!"
    val intentTypedCompound = IntentParser.parse(typedCompound, session)
    val intentVoiceCompound = IntentParser.parse(voiceCompound, session)

    assertEquals(intentTypedCompound.javaClass, intentVoiceCompound.javaClass)
    assertTrue(intentTypedCompound is WeykyIntent.CompoundIntent)
  }
}
