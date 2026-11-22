package com.example.simultrans

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import java.util.Calendar

/** Límites gratuitos diarios. */
object Limites {
    const val MINUTOS_TRADUCCION = 15
    const val PREGUNTAS = 15
}

/** Contadores de uso diario, guardados en local; se reinician cada día. */
class UsoDiario(context: Context) {
    private val prefs = context.getSharedPreferences("simultrans_uso", Context.MODE_PRIVATE)

    private fun hoy(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun revisarDia() {
        if (prefs.getInt("dia", -1) != hoy()) {
            prefs.edit().putInt("dia", hoy()).putLong("ms_traduccion", 0L).putInt("preguntas", 0).apply()
        }
    }

    fun msTraduccionRestantes(): Long {
        revisarDia()
        return (Limites.MINUTOS_TRADUCCION * 60_000L - prefs.getLong("ms_traduccion", 0L)).coerceAtLeast(0L)
    }

    fun preguntasRestantes(): Int {
        revisarDia()
        return (Limites.PREGUNTAS - prefs.getInt("preguntas", 0)).coerceAtLeast(0)
    }

    fun sumarTraduccion(ms: Long) {
        revisarDia()
        prefs.edit().putLong("ms_traduccion", prefs.getLong("ms_traduccion", 0L) + ms).apply()
    }

    fun sumarPregunta() {
        revisarDia()
        prefs.edit().putInt("preguntas", prefs.getInt("preguntas", 0) + 1).apply()
    }
}

/**
 * Suscripción Pro con Google Play Billing 8.
 * Productos (se crean en Play Console, Monetizar > Suscripciones):
 *   simultrans_pro  con planes base "mensual" y "anual".
 */
class Suscripcion(
    private val context: Context,
    private val alCambiar: () -> Unit
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCTO = "simultrans_pro"
    }

    private val prefs = context.getSharedPreferences("simultrans_uso", Context.MODE_PRIVATE)

    /** Estado Pro en caché (se actualiza al consultar Google Play). */
    var esPro: Boolean = prefs.getBoolean("pro", false)
        private set

    private var detalles: ProductDetails? = null
    val planes: List<ProductDetails.SubscriptionOfferDetails>
        get() = detalles?.subscriptionOfferDetails.orEmpty()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun conectar() {
        if (client.isReady) { consultar(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) consultar()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    fun consultar() {
        // Compras actuales
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { r, compras ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) procesar(compras)
        }
        // Precios
        val prod = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCTO).setProductType(BillingClient.ProductType.SUBS).build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(prod)).build()
        ) { r, resultado ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                detalles = resultado.productDetailsList.firstOrNull()
                alCambiar()
            }
        }
    }

    private fun procesar(compras: List<Purchase>) {
        var pro = false
        for (c in compras) {
            if (c.purchaseState == Purchase.PurchaseState.PURCHASED) {
                pro = true
                if (!c.isAcknowledged) {
                    client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(c.purchaseToken).build()
                    ) { }
                }
            }
        }
        if (pro != esPro) {
            esPro = pro
            prefs.edit().putBoolean("pro", pro).apply()
        }
        alCambiar()
    }

    override fun onPurchasesUpdated(r: BillingResult, compras: List<Purchase>?) {
        if (r.responseCode == BillingClient.BillingResponseCode.OK && compras != null) procesar(compras)
    }

    /** Precio legible de un plan (fase de cobro final). */
    fun precio(oferta: ProductDetails.SubscriptionOfferDetails): String =
        oferta.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: ""

    fun comprar(activity: Activity, oferta: ProductDetails.SubscriptionOfferDetails) {
        val d = detalles ?: return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(d).setOfferToken(oferta.offerToken).build()
            )
        ).build()
        client.launchBillingFlow(activity, params)
    }

    fun cerrar() { if (client.isReady) client.endConnection() }
}
