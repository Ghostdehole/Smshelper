package com.ghost.smshelper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConfigRegressionTest {
    private fun carrier(id: String = "CMCC", target: String = "10086", rule: String = "余额([0-9.]+)") =
        CarrierConfig(id, id, target, listOf("1011"), mapOf("balance" to rule), emptyList())

    private fun profile(id: String, vararg carriers: CarrierConfig) = ConfigProfile(id, id, "本地", "{}", carriers.toList())

    private fun json(pattern: String = "余额([0-9.]+)"): String = JSONObject().apply {
        put("carriers", org.json.JSONArray().put(JSONObject().apply {
            put("identifier", "CMCC"); put("name", "移动"); put("targetNumber", "10086")
            put("regexRules", JSONObject().put("balance", pattern))
        }))
    }.toString()

    @Test fun replacingSelectedProfileRefreshesItsConfigAndPreservesOtherSelections() {
        val catalog = ConfigCatalog()
        catalog.replaceProfile(profile("remote", carrier(), carrier("CUCC", "10010")))
        catalog.replaceProfile(profile("custom", carrier("CUCC", "10010", "余额为([0-9.]+)")))
        assertTrue(catalog.select("CUCC", "custom"))
        val updated = carrier(rule = "余额是([0-9.]+)")
        catalog.replaceProfile(profile("remote", updated, carrier("CUCC", "10010")))
        assertEquals(updated, catalog.selected("CMCC"))
        assertEquals("custom", catalog.selections["CUCC"]?.profileId)
    }

    @Test fun removingCarrierFromRemoteProfileCannotLeaveStaleSelectedConfig() {
        val catalog = ConfigCatalog()
        catalog.replaceProfile(profile("remote", carrier(), carrier("CUCC", "10010")))
        catalog.replaceProfile(profile("backup", carrier()))
        catalog.replaceProfile(profile("remote", carrier("CUCC", "10010")))
        assertEquals("backup", catalog.selections["CMCC"]?.profileId)
        assertFalse(catalog.select("CMCC", "remote"))
        assertTrue(catalog.removeProfile("backup"))
        assertNull(catalog.selected("CMCC"))
    }

    @Test fun firstSubscriptionUpdateLinksProfileAndLaterUpdatesReuseIt() {
        val catalog = ConfigCatalog()
        val url = "https://example.com/rules.json"
        catalog.subscriptions[url] = Subscription(url, "远程", 60, 0L, null)
        assertTrue(catalog.applySubscription(url, json(), listOf(carrier()), 100L))
        val id = catalog.subscriptions[url]?.profileId
        assertNotNull(id)
        val updated = carrier(rule = "新余额([0-9.]+)")
        assertTrue(catalog.applySubscription(url, json("新余额([0-9.]+)"), listOf(updated), 200L))
        assertEquals(id, catalog.subscriptions[url]?.profileId)
        assertEquals(1, catalog.profiles.size)
        assertEquals(updated, catalog.selected("CMCC"))
        assertEquals(200L, catalog.subscriptions[url]?.lastUpdateTime)
        assertTrue(catalog.removeProfile(id!!))
        assertNull(catalog.subscriptions[url]?.profileId)
        assertNull(catalog.selected("CMCC"))
    }

    @Test fun legacySubscriptionMissingLinkReusesItsExistingProfile() {
        val catalog = ConfigCatalog()
        val url = "https://example.com/rules.json"
        catalog.replaceProfile(profile("legacy", carrier()).copy(source = "订阅: $url"))
        catalog.subscriptions[url] = Subscription(url, "远程", 60, 10L, null)
        catalog.recoverSubscriptionLinks()
        assertEquals("legacy", catalog.subscriptions[url]?.profileId)
        catalog.applySubscription(url, json(), listOf(carrier()), 20L)
        assertEquals(setOf("legacy"), catalog.profiles.keys)
    }

    @Test fun schemaRejectsInvalidRegexMissingCapturesAndEmptyCarriers() {
        listOf(json("["), json("余额[0-9]+"), "{\"carriers\":[]}", "{\"carriers\":null}").forEach {
            assertTrue("Should reject invalid profile: $it", runCatching { ConfigValidation.parse(it) }.isFailure)
        }
        val bill = JSONObject(json()).apply {
            getJSONArray("carriers").getJSONObject(0).getJSONObject("regexRules").put("bill", "月份([0-9]+)")
        }.toString()
        assertTrue(runCatching { ConfigValidation.parse(bill) }.isFailure)
        assertEquals("10086", ConfigValidation.parse(json()).single().targetNumber)
    }

    @Test fun schemaRejectsWrongCommandTypesAndDuplicateCarrierIdentifiers() {
        val duplicate = JSONObject(json())
        duplicate.getJSONArray("carriers").put(duplicate.getJSONArray("carriers").getJSONObject(0))
        assertTrue(runCatching { ConfigValidation.parse(duplicate.toString()) }.isFailure)
        val wrongCommand = """{"carriers":[{"identifier":"CMCC","name":"移动","targetNumber":"10086","categories":[{"title":"参数","commands":[{"code":"MX","name":"账单","requiresInput":"true"}]}]}]}"""
        assertTrue(runCatching { ConfigValidation.parse(wrongCommand) }.isFailure)
    }

    @Test fun customJavaRegexKeepsLiteralTabsAndNewlines() {
        val pattern = "余额\t\n([0-9]+)"
        val parsed = ConfigValidation.parse(json(pattern)).single()
        assertEquals(pattern, parsed.regexRules["balance"])
        assertEquals("42", Regex(parsed.regexRules.getValue("balance")).find("余额\t\n42")?.groupValues?.get(1))
    }

    @Test fun identityDoesNotConfuseForeignMncOrPartialSenderWithChinaCarrier() {
        assertEquals("CMCC", CarrierIdentity.identifier("460", "00", ""))
        assertEquals("CUCC", CarrierIdentity.identifier("460", "1", ""))
        assertNotEquals("CMCC", CarrierIdentity.identifier("310", "00", "Foreign network"))
        assertNull(CarrierIdentity.identifier("310", "00", "CMCC"))
        assertNull(CarrierIdentity.identifier("460", "", ""))
        assertTrue(CarrierIdentity.matches("+86 10086", "10086"))
        assertTrue(CarrierIdentity.matches("008610086", "10086"))
        listOf("100860", "110086", "100", "", "Bank10086", "+86100860").forEach {
            assertFalse("Partial sender $it must not match", CarrierIdentity.matches(it, "10086"))
        }
    }

    @Test fun subscriptionUrlsAreBoundedNetworkUrlsOnly() {
        assertEquals("https://example.com/rules.json", ConfigValidation.subscriptionUrl(" https://example.com/rules.json "))
        assertNotNull(ConfigValidation.subscriptionUrl("http://example.com/rules.json"))
        listOf("file:///etc/passwd", "ftp://example.com/rules", "https://user:pass@example.com/rules", "https://example.com/#fragment", "https:///missing-host").forEach {
            assertNull(ConfigValidation.subscriptionUrl(it))
        }
    }

    @Test fun bundledCommandsKeepAllFeaturesWithoutSendingLiteralPlaceholders() {
        val directory = File("src/main/res/raw").takeIf { it.isDirectory } ?: File("app/src/main/res/raw")
        val expected = mapOf("cmcc" to 31, "cucc" to 36, "ctcc" to 25, "cbn" to 20)
        expected.forEach { (suffix, count) ->
            val config = ConfigValidation.parse(File(directory, "default_config_$suffix.json").readText()).single()
            val commands = config.categories.flatMap { it.commands }
            assertEquals(count, commands.size)
            assertTrue(commands.none { "[citation:" in it.name })
            assertTrue(commands.none { it.code.any { char -> char.code in 0x4E00..0x9FFF } })
            if (suffix == "cucc") {
                listOf("MX", "LJDXJB#", "CXGSD#", "HZ#", "GBJC#").forEach { prefix ->
                    val command = commands.single { it.code == prefix }
                    assertTrue(command.requiresInput)
                    assertTrue(command.inputHint.isNotBlank())
                }
            }
            val remaining = Regex(config.regexRules.getValue("remainingData"))
            assertEquals("0 MB", remaining.find("套餐剩余流量：0 MB")?.groupValues?.get(1))
            assertEquals("1gb 500 mb", remaining.find("套餐剩余流量 1gb 500 mb")?.groupValues?.get(1))
        }
    }

    @Test fun parameterValidationCoversMonthPhoneAndMultiPartReport() {
        val month = ActionCommand("MX", "账单", true)
        assertNull(CommandParameters.validate(month, "202612"))
        assertNotNull(CommandParameters.validate(month, "202613"))
        assertNotNull(CommandParameters.validate(month, ""))
        assertNull(CommandParameters.validate(ActionCommand("HZ#", "呼转", true), "13800138000"))
        assertNotNull(CommandParameters.validate(ActionCommand("HZ#", "呼转", true), "呼转号码"))
        val report = ActionCommand("LJDXJB#", "举报", true)
        assertNull(CommandParameters.validate(report, "13800138000#垃圾短信内容"))
        assertNotNull(CommandParameters.validate(report, "13800138000#"))
        assertNotNull(CommandParameters.validate(report, "13800138000\n#垃圾短信内容"))
    }
}
