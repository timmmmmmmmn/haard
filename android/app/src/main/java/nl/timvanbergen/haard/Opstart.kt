package nl.timvanbergen.haard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Na het aanzetten van de tablet (of na een update) staat Haard meteen weer klaar. */
class Opstart : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Instellingen.init(context)
        runCatching { HaardService.start(context) }
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
