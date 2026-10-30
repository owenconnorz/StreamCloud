package com.streamcloud.app.data.util

import com.streamcloud.app.data.preserveYtMusicAvatar
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleAccountHelperTest {
    @Test
    fun accountPhotoUrlUsesLastAccountPhotoThumbnail() {
        val response = Json.parseToJsonElement(
            """
            {
              "actions": [{
                "openPopupAction": {
                  "popup": {
                    "multiPageMenuRenderer": {
                      "header": {
                        "activeAccountHeaderRenderer": {
                          "accountPhoto": {
                            "thumbnails": [
                              {"url": "https://lh3.googleusercontent.com/a/photo=s48-c"},
                              {"url": "https://lh3.googleusercontent.com/a/photo=s256-c"}
                            ]
                          }
                        }
                      }
                    }
                  }
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://lh3.googleusercontent.com/a/photo=s256-c",
            GoogleAccountHelper.accountPhotoUrl(response),
        )
    }

    @Test
    fun accountPhotoUrlIgnoresActionsWithoutAnAccountPhoto() {
        val response = Json.parseToJsonElement("""{"actions":[{},{"openPopupAction":{}}]}""")

        assertNull(GoogleAccountHelper.accountPhotoUrl(response))
    }

    @Test
    fun blankLoginPhotoDoesNotErasePreviouslySavedAvatar() {
        assertEquals("https://example.com/old.jpg", preserveYtMusicAvatar("", "https://example.com/old.jpg"))
        assertEquals("https://example.com/new.jpg", preserveYtMusicAvatar("https://example.com/new.jpg", "https://example.com/old.jpg"))
    }
}
