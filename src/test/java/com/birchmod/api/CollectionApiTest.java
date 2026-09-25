package com.birchmod.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birchmod.util.HttpUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The API lookup, over responses shaped like the ones Hypixel really sends.
 *
 * The reported symptom was "I set my key and name and nothing shows". Three
 * separate things caused it: a key sent the way Hypixel stopped accepting, an
 * endpoint with no Skyblock data in it at all, and a failure vocabulary that
 * said "api error" to four problems with four different fixes.
 */
class CollectionApiTest {

    private static final String UUID = "069a79f444e94726a5befca90e38aaf5";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    @DisplayName("a key is recognised as a key before it is ever sent")
    void keyShapeIsChecked() {
        assertTrue(CollectionApi.looksLikeKey("0a1b2c3d-4e5f-6789-abcd-ef0123456789"));
        assertTrue(CollectionApi.looksLikeKey("0a1b2c3d4e5f6789abcdef0123456789"));
        assertTrue(CollectionApi.looksLikeKey("0A1B2C3D-4E5F-6789-ABCD-EF0123456789"),
                "upper case is still hex");

        assertFalse(CollectionApi.looksLikeKey("Notch"), "a username is not a key");
        assertFalse(CollectionApi.looksLikeKey(""));
        assertFalse(CollectionApi.looksLikeKey(null));
        assertFalse(CollectionApi.looksLikeKey("0a1b2c3d4e5f6789abcdef012345678"), "one short");
        assertFalse(CollectionApi.looksLikeKey("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"));
        assertFalse(CollectionApi.looksLikeKey("\"0a1b2c3d4e5f6789abcdef0123456789\""),
                "a quote left on from copying");
    }

    @Test
    @DisplayName("every kind of failure says which one it was")
    void failuresAreNamed() {
        String rejected = "API key rejected";

        assertEquals("no connection", CollectionApi.describe(
                new HttpUtil.Response(HttpUtil.NO_RESPONSE, null), rejected));
        assertEquals(rejected, CollectionApi.describe(
                new HttpUtil.Response(403, null), rejected), "403 is a bad key");
        assertEquals(rejected, CollectionApi.describe(
                new HttpUtil.Response(400, null), rejected),
                "400 is what Hypixel sends for a missing key");
        assertEquals("rate limited, retrying", CollectionApi.describe(
                new HttpUtil.Response(429, null), rejected));
        assertEquals("not found", CollectionApi.describe(
                new HttpUtil.Response(404, null), rejected));
        assertEquals("api error 503", CollectionApi.describe(
                new HttpUtil.Response(503, null), rejected));
    }

    @Test
    @DisplayName("a response knows what it is")
    void responseClassifiesItself() {
        assertTrue(new HttpUtil.Response(200, "{}").ok());
        assertFalse(new HttpUtil.Response(200, null).ok(), "200 with no body is not ok");
        assertTrue(new HttpUtil.Response(HttpUtil.NO_RESPONSE, null).unreachable());
        assertTrue(new HttpUtil.Response(403, null).unauthorised());
        assertTrue(new HttpUtil.Response(429, null).rateLimited());
        assertTrue(new HttpUtil.Response(404, null).notFound());
    }

    @Test
    @DisplayName("the total comes off the profile you are playing")
    void selectedProfileWins() {
        String body = """
            {"success":true,"profiles":[
              {"cute_name":"Apple","selected":false,"members":{"%s":
                 {"collection":{"LOG":900,"LOG:2":50}}}},
              {"cute_name":"Mango","selected":true,"members":{"%s":
                 {"collection":{"LOG:2":1234567,"LOG:1":42}}}},
              {"cute_name":"Kiwi","selected":false,"members":{"%s":
                 {"collection":{"LOG:2":9999999}}}}
            ]}""".formatted(UUID, UUID, UUID);

        CollectionApi api = new CollectionApi();
        api.readProfiles(json(body), UUID);

        assertEquals(1234567L, api.getBirchCollected(), "the selected profile, not the biggest");
        assertEquals("Mango", api.getProfileName());
        assertEquals("ok", api.getStatus());
    }

    @Test
    @DisplayName("with nothing selected, the largest total is the better guess")
    void unselectedFallsBackToLargest() {
        String body = """
            {"success":true,"profiles":[
              {"cute_name":"Apple","members":{"%s":{"collection":{"LOG:2":50}}}},
              {"cute_name":"Kiwi","members":{"%s":{"collection":{"LOG:2":700}}}}
            ]}""".formatted(UUID, UUID);

        CollectionApi api = new CollectionApi();
        api.readProfiles(json(body), UUID);
        assertEquals(700L, api.getBirchCollected());
    }

    @Test
    @DisplayName("birch is LOG:2, and the other woods are not birch")
    void onlyBirchCounts() {
        String body = """
            {"success":true,"profiles":[
              {"cute_name":"Apple","selected":true,"members":{"%s":
                 {"collection":{"LOG":5000,"LOG_2":600,"LOG:1":70,"LOG:3":8}}}}]}"""
                .formatted(UUID);

        CollectionApi api = new CollectionApi();
        api.readProfiles(json(body), UUID);
        assertEquals(0L, api.getBirchCollected(), "oak, acacia, spruce and jungle are not birch");
    }

    @Test
    @DisplayName("a profile that will not share collections is not an error")
    void collectionsSwitchedOff() {
        String body = """
            {"success":true,"profiles":[
              {"cute_name":"Apple","selected":true,"members":{"%s":{"experience":5}}}]}"""
                .formatted(UUID);

        CollectionApi api = new CollectionApi();
        api.readProfiles(json(body), UUID);
        assertEquals("collections not shared", api.getStatus(),
                "this has its own fix, and looks exactly like a bad key without it");
        assertFalse(api.hasCollection());
    }

    @Test
    @DisplayName("no profile, a null list, and somebody else's profile")
    void theEmptyCases() {
        CollectionApi empty = new CollectionApi();
        empty.readProfiles(json("{\"success\":true,\"profiles\":[]}"), UUID);
        assertEquals("no Skyblock profile", empty.getStatus());

        CollectionApi nulled = new CollectionApi();
        nulled.readProfiles(json("{\"success\":true,\"profiles\":null}"), UUID);
        assertEquals("no Skyblock profile", nulled.getStatus());

        String otherPlayer = """
            {"success":true,"profiles":[
              {"cute_name":"Apple","members":{"ffffffffffffffffffffffffffffffff":
                 {"collection":{"LOG:2":10}}}}]}""";
        CollectionApi other = new CollectionApi();
        other.readProfiles(json(otherPlayer), UUID);
        assertFalse(other.hasCollection(), "we are not a member of that profile");
    }

    @Test
    @DisplayName("never having cut birch reads as zero, not as missing")
    void zeroIsARealAnswer() {
        String body = """
            {"success":true,"profiles":[
              {"cute_name":"Apple","selected":true,"members":{"%s":
                 {"collection":{"LOG":10}}}}]}""".formatted(UUID);

        CollectionApi api = new CollectionApi();
        api.readProfiles(json(body), UUID);
        assertTrue(api.hasCollection());
        assertEquals(0L, api.getBirchCollected());
    }
}
