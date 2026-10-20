package com.example.core.intelligence.battery

import com.example.core.intelligence.ContextDatum
import com.example.core.intelligence.ContextReasoningEngine
import com.example.core.intelligence.EpistemicStatus
import com.example.core.intelligence.SituationSnapshot
import com.example.core.intelligence.conversation.ConversationSession
import com.example.core.intelligence.conversation.ConversationalReasoner
import com.example.core.intelligence.time.SettableTimeProvider
import com.example.core.intent.IntentParser
import com.example.core.intent.WeykyIntent
import com.example.device.battery.BatterySnapshot
import com.example.device.battery.ChargingState
import com.example.device.battery.PowerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Phase 2 Acceptance Tests: Time-Aware Battery Reasoning and Contextual Follow-Ups.
 *
 * Verifies the 12 core requirements specified in Phase 2:
 * 1. Correct elapsed time between current and target time.
 * 2. Correct projected range using grounded discharge rate assumptions (~2-4%/hr, +4-8%/hr music).
 * 3. Zero or negative values bounded correctly to 0..100%.
 * 4. Explicit handling of target times already passed today (no silent projection to next day).
 * 5. Handling of missing or unavailable battery telemetry without guessing.
 * 6. Separate charging-state handling (never applies discharge formula while charging).
 * 7. Explicit ESTIMATE labelling and full assumption disclosure.
 * 8. Contextual follow-up with navigation retaining previous horizon, music, and initial readings.
 * 9. Unsupported navigation consumption model honestly stated without fabricating fake numbers.
 * 10. Regression testing of existing music-only battery hypothetical impact.
 * 11. Regression testing of existing conversational follow-ups (phone specs, travel, sleep, etc.).
 * 12. Regression testing of compound command parsing and sequential execution.
 */
class TimeAwareBatteryReasoningTest {

  private fun createMockSnapshot(
    batteryDatum: ContextDatum<BatterySnapshot>
  ): SituationSnapshot {
    return SituationSnapshot(
      timestampMillis = 1000L,
      battery = batteryDatum,
      network = ContextDatum.unavailable("NetworkProvider", "Test"),
      physicalContext = ContextDatum.unavailable("ContextEngine", "Test"),
      location = ContextDatum.unavailable("LocationProvider", "Test"),
      weather = ContextDatum.unavailable("WeatherProvider", "Test"),
      calendar = ContextDatum.unavailable("CalendarProvider", "Test"),
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
      temperatureCelsius = 25.0f,
      voltageMillivolts = 3850,
      healthString = "Good",
      isAvailable = percentage >= 0
    )
  }

  // --- Requirement 1: Correct elapsed time calculation ---

  @Test
  fun testRequirement1_correctElapsedTimeCalculation() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 14, minute = 0, second = 0) // 2:00 PM
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    val snapshot = createMockSnapshot(
      ContextDatum.fact(createBatterySnapshot(percentage = 80), "BatteryProvider")
    )

    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = false, isArabic = true)
    assertTrue("Outcome must be Success", outcome is BatteryForecastOutcome.Success)
    val success = outcome as BatteryForecastOutcome.Success

    // 18:00 - 14:00 = exactly 4.0 hours
    assertEquals(4.0, success.horizonHours, 0.01)
    assertEquals(4.0, success.scenario.horizonHours, 0.01)
  }

  // --- Requirement 2: Correct projected range using supported assumptions ---

  @Test
  fun testRequirement2_correctProjectedRange_baselineAndMusic() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 14, minute = 0, second = 0) // 2:00 PM
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    val snapshot = createMockSnapshot(
      ContextDatum.fact(createBatterySnapshot(percentage = 80), "BatteryProvider")
    )

    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "6:00 PM"
    )

    // Scenario A: Typical usage (2.0% - 4.0% per hour over 4.0 hours => drain 8.0% - 16.0%)
    // Starting battery 80% => projected range 64% - 72%
    val baselineOutcome = calculator.calculateForecast(snapshot, target, includesMusic = false, isArabic = false)
    assertTrue(baselineOutcome is BatteryForecastOutcome.Success)
    val baselineSuccess = baselineOutcome as BatteryForecastOutcome.Success
    assertEquals(64, baselineSuccess.estimatedMinPct)
    assertEquals(72, baselineSuccess.estimatedMaxPct)

    // Scenario B: Music included (baseline 2-4% + music 4-8% = 6-12%/hr over 4.0 hours => drain 24% - 48%)
    // Starting battery 80% => projected range 32% - 56%
    val musicOutcome = calculator.calculateForecast(snapshot, target, includesMusic = true, isArabic = false)
    assertTrue(musicOutcome is BatteryForecastOutcome.Success)
    val musicSuccess = musicOutcome as BatteryForecastOutcome.Success
    assertEquals(32, musicSuccess.estimatedMinPct)
    assertEquals(56, musicSuccess.estimatedMaxPct)
  }

  // --- Requirement 3: Zero or negative values bounded correctly ---

  @Test
  fun testRequirement3_zeroOrNegativeProjectedValuesBoundedCorrectly() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 14, minute = 0, second = 0) // 2:00 PM
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    // Starting at only 15% battery for a 4-hour horizon with music (drain 24% - 48%)
    val snapshot = createMockSnapshot(
      ContextDatum.fact(createBatterySnapshot(percentage = 15), "BatteryProvider")
    )

    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = true, isArabic = true)
    assertTrue(outcome is BatteryForecastOutcome.Success)
    val success = outcome as BatteryForecastOutcome.Success

    // Values must be clamped to 0..100% and never negative
    assertTrue("Estimated min must be >= 0", success.estimatedMinPct >= 0)
    assertTrue("Estimated max must be >= 0", success.estimatedMaxPct >= 0)
    assertEquals(0, success.estimatedMinPct)
    assertEquals(0, success.estimatedMaxPct)

    // Speech must disclose depletion warning and recommend charging or power saving
    assertTrue(success.synthesis.synthesizedSpeech.contains("0%"))
    assertNotNull("Should provide proactive next step when battery will deplete", success.synthesis.nextStep)
  }

  // --- Requirement 4: Explicit handling of target times already passed today ---

  @Test
  fun testRequirement4_targetTimeAlreadyPassedHandledExplicitly() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 19, minute = 15, second = 0) // 7:15 PM
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    val snapshot = createMockSnapshot(
      ContextDatum.fact(createBatterySnapshot(percentage = 75), "BatteryProvider")
    )

    // Target is 18:00 (6:00 PM), which is already in the past today
    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = false, isArabic = true)
    assertTrue("Must return TargetTimePassed outcome", outcome is BatteryForecastOutcome.TargetTimePassed)
    val passedOutcome = outcome as BatteryForecastOutcome.TargetTimePassed

    // Must not silently project to tomorrow
    assertTrue(passedOutcome.synthesis.synthesizedSpeech.contains("قد مضت بالفعل اليوم"))
    assertTrue(passedOutcome.synthesis.synthesizedSpeech.contains("لا يمكن إجراء تقدير زمني لوقت في الماضي"))
  }

  // --- Requirement 5: Missing battery telemetry handled without guessing ---

  @Test
  fun testRequirement5_missingBatteryTelemetryHandledWithoutGuessing() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 14, minute = 0, second = 0)
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    // Battery datum is UNAVAILABLE
    val snapshot = createMockSnapshot(
      ContextDatum.unavailable("BatteryProvider", "Battery service permission denied")
    )

    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "المساء",
      descriptionEnglish = "evening",
      rawMatchedString = "المساء"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = false, isArabic = true)
    assertTrue(outcome is BatteryForecastOutcome.TelemetryUnavailable)
    val unavailable = outcome as BatteryForecastOutcome.TelemetryUnavailable

    assertEquals(0.0f, unavailable.synthesis.confidenceScore, 0.001f)
    assertTrue(unavailable.synthesis.synthesizedSpeech.contains("غير متوفرة حالياً"))
  }

  // --- Requirement 6: Separate charging-state handling ---

  @Test
  fun testRequirement6_chargingStateTreatedSeparatelyWithoutDischargeFormula() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 14, minute = 0, second = 0)
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    // Device is actively charging on AC power
    val snapshot = createMockSnapshot(
      ContextDatum.fact(
        createBatterySnapshot(
          percentage = 55,
          chargingState = ChargingState.CHARGING,
          powerSource = PowerSource.AC
        ),
        "BatteryProvider"
      )
    )

    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = true, isArabic = true)
    assertTrue("Must return Charging outcome", outcome is BatteryForecastOutcome.Charging)
    val chargingOutcome = outcome as BatteryForecastOutcome.Charging

    assertTrue("Charging scenario must indicate isCharging = true", chargingOutcome.scenario.isCharging)
    assertTrue(chargingOutcome.synthesis.synthesizedSpeech.contains("قيد الشحن"))
    assertTrue(chargingOutcome.synthesis.synthesizedSpeech.contains("فلن تنفد البطارية"))
  }

  // --- Requirement 7: Explicit ESTIMATE status and disclosure of assumptions ---

  @Test
  fun testRequirement7_explicitEstimateLabelAndDisclosedAssumptions() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 12, minute = 0, second = 0) // 12:00 PM noon
    }
    val calculator = BatteryForecastCalculator(timeProvider)

    val snapshot = createMockSnapshot(
      ContextDatum.fact(createBatterySnapshot(percentage = 90), "BatteryProvider")
    )

    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    val outcome = calculator.calculateForecast(snapshot, target, includesMusic = true, isArabic = false)
    assertTrue(outcome is BatteryForecastOutcome.Success)
    val success = outcome as BatteryForecastOutcome.Success

    // Must clearly state ESTIMATE
    assertTrue(success.synthesis.synthesizedSpeech.contains("[ESTIMATE]"))
    // Must distinguish actual measurement from statistical estimate
    assertTrue(success.synthesis.synthesizedSpeech.contains("Confirmed actual measurement: Current battery is at 90%"))
    // Must disclose that endurance depends on real factors
    assertTrue(success.synthesis.synthesizedSpeech.contains("not a device-specific measurement"))
  }

  // --- Requirements 8 & 9: Navigation follow-up retaining horizon and not fabricating numbers ---

  @Test
  fun testRequirement8And9_navigationFollowUpRetainsHorizonAndRefusesFabrication() {
    val timeProvider = SettableTimeProvider().apply {
      setTime(hourOfDay = 14, minute = 0, second = 0)
    }
    val engine = ContextReasoningEngine(timeProvider)

    val snapshot = createMockSnapshot(
      ContextDatum.fact(createBatterySnapshot(percentage = 80), "BatteryProvider")
    )

    val target = BatteryForecastTarget(
      targetHour = 18,
      targetMinute = 0,
      descriptionArabic = "الساعة 6:00 مساءً",
      descriptionEnglish = "6:00 PM",
      rawMatchedString = "السادسة"
    )

    // Turn 1: Initial forecast with music
    val initialOutcome = engine.reasonBatteryForecast(snapshot, target, includesMusic = true, isArabic = true)
    assertTrue(initialOutcome is BatteryForecastOutcome.Success)
    val initialScenario = (initialOutcome as BatteryForecastOutcome.Success).scenario

    assertEquals(4.0, initialScenario.horizonHours, 0.01)
    assertTrue(initialScenario.includesMusic)
    assertFalse(initialScenario.includesNavigation)

    // Turn 2: User follow-up: "وماذا لو استخدمت الملاحة أيضًا؟"
    val navLimitation = engine.reasonNavigationFollowUp(initialScenario, isArabic = true)

    // Retained relevant scenario fields
    assertEquals("Must retain horizon", 4.0, navLimitation.updatedScenario.horizonHours, 0.01)
    assertEquals("Must retain target hour", 18, navLimitation.updatedScenario.targetHour)
    assertEquals("Must retain target minute", 0, navLimitation.updatedScenario.targetMinute)
    assertTrue("Must retain music inclusion", navLimitation.updatedScenario.includesMusic)
    assertTrue("Must flag navigation included", navLimitation.updatedScenario.includesNavigation)

    // Epistemic honesty: must NOT fabricate a numerical drain rate or percentage for navigation
    val speech = navLimitation.synthesis.synthesizedSpeech
    assertTrue("Must mention navigation and GPS impact", speech.contains("الملاحة") && speech.contains("GPS"))
    assertTrue("Must declare that numerical model is unavailable", speech.contains("UNAVAILABLE") || speech.contains("غير متاح"))
    assertTrue("Must explain that no calibrated model is available", speech.contains("لا يمكن تقديم نسبة مئوية رقمية محددة"))
    assertTrue("Must recommend connecting car charger", speech.contains("شاحن سيارة") || speech.contains("شاحن السيارة"))
  }

  // --- Requirement 10: Regression of existing music-only battery estimate ---

  @Test
  fun testRequirement10_regressionOfMusicOnlyBatteryEstimate() {
    val engine = ContextReasoningEngine()
    val snapshot = createMockSnapshot(
      ContextDatum.fact(createBatterySnapshot(percentage = 60), "BatteryProvider")
    )

    val synthesis = engine.reasonHypotheticalBatteryMusic(snapshot, isArabic = true)
    assertTrue("Synthesis must disclose 4% to 8% range", synthesis.synthesizedSpeech.contains("4%") && synthesis.synthesizedSpeech.contains("8%"))
    assertTrue("Synthesis must state it is an estimate", synthesis.synthesizedSpeech.contains("تقدير إحصائي"))
    assertEquals("استهلاك الموسيقى (تقديري)", synthesis.primaryDecision)
  }

  // --- Requirement 11: Regression of conversational follow-ups ---

  @Test
  fun testRequirement11_regressionOfConversationalFollowUps() {
    val reasoner = ConversationalReasoner()
    val session = ConversationSession()

    // Query 1: Travel to Salalah
    val r1 = reasoner.reason("حدثني عن صلالة", session)
    session.recordAssistantTurn(r1.replyText, r1.proactiveSuggestions, r1.topicDetected)
    assertEquals("travel", session.currentTopic)

    // Follow-up 1: "وماذا عن الطقس والملابس هناك؟"
    val r2 = reasoner.reason("وماذا عن الطقس والملابس هناك؟", session)
    assertTrue("Should resolve travel weather follow-up", r2.replyText.contains("صلالة") || r2.replyText.contains("الخريف"))

    // Query 2: Sleep advice
    val r3 = reasoner.reason("كيف أحسن نومي؟", session)
    session.recordAssistantTurn(r3.replyText, r3.proactiveSuggestions, r3.topicDetected)
    assertEquals("sleep_wellness", session.currentTopic)

    // Follow-up 2: "وماذا عن القهوة؟"
    val r4 = reasoner.reason("وماذا عن القهوة؟", session)
    assertTrue("Should resolve caffeine sleep follow-up", r4.replyText.contains("الكافيين") || r4.replyText.contains("القهوة"))
  }

  // --- Requirement 12: Regression of compound command parsing and execution ---

  @Test
  fun testRequirement12_regressionOfCompoundCommandParsing() {
    val query = "افتح الموسيقى ثم اخفض الصوت إلى 30%"
    val parsed = IntentParser.parse(query)
    assertTrue("Must parse as CompoundIntent", parsed is WeykyIntent.CompoundIntent)
    val compound = parsed as WeykyIntent.CompoundIntent
    assertEquals(2, compound.steps.size)
    assertTrue(compound.steps[0].intent is WeykyIntent.OpenAppIntent)
    assertTrue(compound.steps[1].intent is WeykyIntent.VolumePercentIntent)
  }

  // --- End-to-end IntentParser test for time-aware battery queries ---

  @Test
  fun testEndToEnd_intentParserTimeAwareBatteryQueries() {
    val session = ConversationSession()

    // 1. "كم ستتبقى من البطارية حتى المساء؟"
    val intent1 = IntentParser.parse("كم ستتبقى من البطارية حتى المساء؟", session)
    assertTrue("Must parse as TimeAwareBatteryForecastIntent", intent1 is WeykyIntent.TimeAwareBatteryForecastIntent)
    val forecast1 = intent1 as WeykyIntent.TimeAwareBatteryForecastIntent
    assertNotNull(forecast1.target)
    assertEquals(18, forecast1.target?.targetHour)
    assertFalse(forecast1.includesMusic)
    assertFalse(forecast1.isFollowUpNavigation)

    // 2. "كم ستتبقى البطارية حتى السادسة إذا استمعت للموسيقى؟"
    val intent2 = IntentParser.parse("كم ستتبقى البطارية حتى السادسة إذا استمعت للموسيقى؟", session)
    assertTrue(intent2 is WeykyIntent.TimeAwareBatteryForecastIntent)
    val forecast2 = intent2 as WeykyIntent.TimeAwareBatteryForecastIntent
    assertNotNull(forecast2.target)
    assertEquals(18, forecast2.target?.targetHour)
    assertTrue(forecast2.includesMusic)
    assertFalse(forecast2.isFollowUpNavigation)

    // Simulate recording active scenario in session
    session.lastBatteryScenario = BatteryForecastScenario(
      targetHour = 18,
      targetMinute = 0,
      targetDescription = "الساعة 6:00 مساءً",
      horizonHours = 4.0,
      includesMusic = true,
      includesNavigation = false,
      initialBatteryPct = 80,
      estimatedMinPct = 32,
      estimatedMaxPct = 56,
      isCharging = false
    )

    // 3. Follow-up: "وماذا لو استخدمت الملاحة أيضًا؟"
    val intent3 = IntentParser.parse("وماذا لو استخدمت الملاحة أيضًا؟", session)
    assertTrue("Must parse as navigation follow-up", intent3 is WeykyIntent.TimeAwareBatteryForecastIntent)
    val navForecast = intent3 as WeykyIntent.TimeAwareBatteryForecastIntent
    assertTrue("isFollowUpNavigation must be true", navForecast.isFollowUpNavigation)
  }
}
