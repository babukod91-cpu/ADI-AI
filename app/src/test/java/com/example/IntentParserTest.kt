package com.example

import com.example.data.model.AssistantAction.*
import com.example.data.model.SystemActionType
import com.example.data.repository.IntentParser
import com.example.data.repository.Replies
import org.junit.Assert.*
import org.junit.Test

class IntentParserTest {
    @Test fun homeBack() {
        assertEquals(SystemControl(SystemActionType.HOME), IntentParser.parse("go home"))
        assertEquals(SystemControl(SystemActionType.BACK), IntentParser.parse("peeche jao"))
    }
    @Test fun volumeAndTorch() {
        assertEquals(SystemControl(SystemActionType.VOLUME_UP), IntentParser.parse("volume badhao"))
        assertEquals(SystemControl(SystemActionType.VOLUME_DOWN), IntentParser.parse("decrease the volume"))
        assertEquals(SystemControl(SystemActionType.FLASHLIGHT), IntentParser.parse("torch on karo"))
    }
    @Test fun swipes() {
        assertEquals(SystemControl(SystemActionType.SWIPE_UP), IntentParser.parse("scroll down"))
        assertEquals(SystemControl(SystemActionType.SWIPE_DOWN), IntentParser.parse("swipe down"))
    }
    @Test fun alarm() {
        assertEquals(SetAlarm(7, 30, "ADI Alarm"), IntentParser.parse("set alarm for 7:30 am"))
        assertEquals(SetAlarm(18, 0, "ADI Alarm"), IntentParser.parse("alarm lagao 6 baje shaam"))
    }
    @Test fun timer() {
        assertEquals(SetTimer(300, "ADI Timer"), IntentParser.parse("set a timer for 5 minutes"))
    }
    @Test fun call() {
        assertEquals(MakePhoneCall("Rahul"), IntentParser.parse("call Rahul"))
        assertEquals(MakePhoneCall("Mom"), IntentParser.parse("Mom ko call karo"))
    }
    @Test fun whatsapp() {
        val a = IntentParser.parse("send message to Rahul saying hello on whatsapp")
        assertTrue(a is SendWhatsApp)
        assertEquals("Rahul", (a as SendWhatsApp).contactOrPhone)
        assertEquals("hello", a.message)
        assertTrue(IntentParser.parse("send whatsapp message") is AnswerText)   // asks who
    }
    @Test fun youtube() {
        assertEquals(SearchYouTube("arijit singh", true), IntentParser.parse("play arijit singh on youtube"))
        assertEquals(OpenApp("youtube"), IntentParser.parse("open youtube"))
    }
    @Test fun maps() { assertEquals(NavigateMaps("India Gate"), IntentParser.parse("navigate to India Gate")) }
    @Test fun openApp() { assertEquals(OpenApp("calculator"), IntentParser.parse("calculator kholo")) }
    @Test fun screen() { assertTrue(IntentParser.parse("what's on my screen") is AskScreen) }
    @Test fun normalQuestionIsNotACommand() {
        assertNull(IntentParser.parse("what is the capital of France"))
        assertNull(IntentParser.parse("tell me a joke"))
    }
    @Test fun replies() {
        assertTrue(Replies.isYes("Haan!")); assertTrue(Replies.isNo("nahi")); assertTrue(Replies.isStop("ruko"))
        assertEquals(1, Replies.ordinal("doosra")); assertNull(Replies.ordinal("banana"))
    }
}
