package com.github.yutaplug.connectioninfo

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.app.AppDialog
import java.util.regex.Pattern

internal class ConnectionActionsDialog(private val account: ConnectedAccount, private val icon: Drawable) :
    AppDialog(Utils.getResId("connected_account_actions_dialog", "layout")) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val header = view.findViewById<TextView>(Utils.getResId("connected_account_actions_dialog_header", "id"))
        header.text = "${account.d()} (${account.g()})"
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(
            icon.constantState?.newDrawable()?.mutate(),
            null,
            null,
            null,
        )
        val copy = view.findViewById<TextView>(Utils.getResId("connected_account_actions_dialog_copy_username", "id"))
        copy.setOnClickListener {
            Utils.setClipboard(account.g(), account.d())
            dismiss()
        }
        val open = view.findViewById<TextView>(Utils.getResId("connected_account_actions_dialog_open_in_browser", "id"))
        val url = profileUrl() ?: return
        open.visibility = View.VISIBLE
        open.setOnClickListener {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            dismiss()
        }
    }

    private fun profileUrl(): String? {
        val name = Uri.encode(account.d())
        return when (account.g()) {
            "tiktok" -> {
                "https://www.tiktok.com/@$name"
            }

            "epicgames" -> {
                null
            }

            "roblox" -> {
                "https://www.roblox.com/users/${Uri.encode(account.b())}/profile"
            }

            "ebay" -> {
                "https://www.ebay.com/usr/$name"
            }

            "instagram" -> {
                "https://www.instagram.com/$name"
            }

            "bluesky" -> {
                "https://bsky.app/profile/$name"
            }

            // A Mastodon username includes its instance; do not assume mastodon.social.
            "mastodon" -> {
                val username = account.d()
                val parts = Pattern
                    .compile(
                        "@",
                    ).split(if (username.startsWith("@")) username.substring(1) else username)
                if (parts.size == 2 && Pattern.matches("[A-Za-z0-9.-]+", parts[1])) {
                    "https://${parts[1]}/@${Uri.encode(parts[0])}"
                } else {
                    null
                }
            }

            "domain" -> {
                "https://${account.d()}".takeIf { Uri.parse(it).host == account.d() }
            }

            else -> {
                null
            }
        }
    }
}
