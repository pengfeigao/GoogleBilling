package com.google.billing;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchaseHistoryRecord;
import com.android.billingclient.api.PurchaseHistoryResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.google.billing.constant.Constant;
import com.google.billing.listener.BaseBillingUpdateListener;
import com.google.billing.receiver.BillingPurchasesReceiver;
import com.google.billing.ui.PurchasesActivity;
import com.google.billing.utils.LogUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用Google Play结算版本2.0及以上，必须在3天内确认所有购买交易。
 * 如果没有正确确认，将导致系统对相应的购买交易按退款处理。
 * 确认购买交易有3种方式：
 * 1. 消耗型商品，客户端使用API consumeAsync()
 * 2. 对于非消耗型商品，客户端使用API acknowledgePurchase()
 * 3. 还可以使用服务器API新增的acknowledge()方法进行消耗确认
 *
 * @author gaopengfei on 2019/05/06.
 */
public class BillingManager implements PurchasesUpdatedListener {

    private BillingClient mBillingClient;
    private boolean mIsServiceConnected;
    private final WeakReference<Activity> weakReference;
    private BaseBillingUpdateListener billingUpdatesListener;
    private BillingPurchasesReceiver billingPurchasesReceiver;

    public BillingManager(Activity activity, BaseBillingUpdateListener billingUpdatesListener) {
        this.weakReference = new WeakReference<>(activity);
        this.billingUpdatesListener = billingUpdatesListener;
    }

    public BillingManager(Activity activity, BillingPurchasesReceiver billingPurchasesReceiver) {
        this.weakReference = new WeakReference<>(activity);
        this.billingPurchasesReceiver = billingPurchasesReceiver;
        IntentFilter intentFilter = new IntentFilter(Constant.ACTION_BILLING);
        LocalBroadcastManager.getInstance(activity).registerReceiver(billingPurchasesReceiver, intentFilter);
    }

    /**
     * 连接谷歌商店(异步)
     */
    public void startServiceConnection(final Runnable runnable) {
        mBillingClient = BillingClient
                .newBuilder(weakReference.get().getApplicationContext())
                .enablePendingPurchases()
                .setListener(this)
                .build();
        mBillingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    // The BillingClient is ready. You can query purchases here.
                    if (billingUpdatesListener != null) {
                        billingUpdatesListener.onBillingClientSetupFinished();
                    }
                    mIsServiceConnected = true;
                    LogUtils.e("Google billing service connect success!");
                    if (runnable != null) {
                        runnable.run();
                    }
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Try to restart the connection on the next request to
                // Google Play by calling the startConnection() method.
                mIsServiceConnected = false;
                LogUtils.e("Google billing service connect fail!");
            }
        });
    }

    /**
     * 异步查询商品信息
     *
     * @param skuId       商品唯一ID
     * @param skuType 商品类型 详见{@link com.android.billingclient.api.BillingClient.SkuType}
     */
    public void querySkuDetailAsyn(final String skuId, final String skuType) {
        LogUtils.e("querySkuDetailAsyn >>> [" + skuId + ",type:" + skuType + "]");
        executeServiceRequest(new Runnable() {
            @Override
            public void run() {
                List<String> skuList = new ArrayList<>();
                skuList.add(skuId);
                final SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
                params.setSkusList(skuList).setType(skuType);
                mBillingClient.querySkuDetailsAsync(params.build(),
                        new SkuDetailsResponseListener() {
                            @Override
                            public void onSkuDetailsResponse(@NonNull BillingResult billingResult, List<SkuDetails> skuDetailsList) {
                                // Process the result.
                                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {
                                    if (billingUpdatesListener != null) {
                                        billingUpdatesListener.onQuerySkuDetailSuccess(skuDetailsList);
                                    }
                                    if (!skuDetailsList.isEmpty()) {
                                        for (SkuDetails skuDetails : skuDetailsList) {
                                            LogUtils.e("querySkuDetailAsyn success >>> [skuDetails:" + skuDetails.toString() + "]");
                                        }
                                    }
                                } else {
                                    if (billingUpdatesListener != null) {
                                        billingUpdatesListener.onQuerySkuDetailFailure(billingResult.getResponseCode(), billingResult.getDebugMessage());
                                    }
                                }
                            }
                        });
            }
        });
    }

    /**
     * 异步查询购买历史商品详情
     * @param skuType 商品类型 {@link com.android.billingclient.api.BillingClient.SkuType}
     */
    public void queryPurchaseHistoryAsync(final @BillingClient.SkuType String skuType){
        LogUtils.e("queryPurchaseHistoryAsync >>> [" + skuType + "]");
        executeServiceRequest(new Runnable() {
            @Override
            public void run() {
                mBillingClient.queryPurchaseHistoryAsync(skuType,new PurchaseHistoryResponseListener() {
                            @Override
                            public void onPurchaseHistoryResponse(@NonNull BillingResult billingResult, List<PurchaseHistoryRecord> list) {
                                if (billingUpdatesListener != null){
                                    billingUpdatesListener.onPurchaseHistoryResponse(billingResult, list);
                                }
                            }
                        });
            }
        });
    }

    /**
     * 确认历史购买，最好在每次启动应用前执行一次，防止有未正常确认的商品而导致三天后退款
     *
     * @param skuType 商品类型 {@link com.android.billingclient.api.BillingClient.SkuType}
     */
    public void confirmHistoryPurchase(final String skuType) {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                /* 同步查询历史购买 */
                Purchase.PurchasesResult purchasesResult = mBillingClient.queryPurchases(skuType);
                if (purchasesResult != null && purchasesResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    List<Purchase> purchasesList = purchasesResult.getPurchasesList();
                    if (purchasesList != null && !purchasesList.isEmpty()) {
                        for (Purchase purchase : purchasesList) {
                            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                if (!purchase.isAcknowledged()) {
                                    if (BillingClient.SkuType.SUBS.equals(skuType)) {
                                        acknowledgePurchase(purchase.getPurchaseToken());
                                        LogUtils.e("acknowledgePurchase success >>> [orderId：" + purchase.getOrderId() + "]");
                                    } else if (BillingClient.SkuType.INAPP.equals(skuType)) {
                                        consumeAsync(purchase.getPurchaseToken());
                                        LogUtils.e("consumeAsync success >>> [orderId：" + purchase.getOrderId() + "]");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        };
        executeServiceRequest(runnable);
    }

    /**
     * <p>
     * 启动内购流程
     * 商品查询成功后直接进入购买流程，如果查询商品失败则直接执行{@link BaseBillingUpdateListener#onPurchasesFailure(int, String)}
     * </p>
     *
     * @param skuId       商品ID
     * @param skuType 商品类型
     */
    public void launchBillingFlow(final String skuId, final String skuType) {
        LogUtils.e("launchBillingFlow > querySkuDetailsAsync >>> [" + skuId + ",type:" + skuType + "]");
        executeServiceRequest(new Runnable() {
            @Override
            public void run() {
                List<String> skuList = new ArrayList<>();
                skuList.add(skuId);
                final SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
                params.setSkusList(skuList).setType(skuType);
                mBillingClient.querySkuDetailsAsync(params.build(),
                        new SkuDetailsResponseListener() {
                            @Override
                            public void onSkuDetailsResponse(@NonNull BillingResult billingResult, List<SkuDetails> skuDetailsList) {
                                // Process the result.
                                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {
                                    if (!skuDetailsList.isEmpty()) {
                                        for (SkuDetails skuDetails : skuDetailsList) {
                                            // 发起内购
                                            launchBillingFlow(skuDetails);
                                            LogUtils.e("querySkuDetailsAsync success >>> [skuDetails:" + skuDetails.toString() + "]");
                                        }
                                    }
                                } else {
                                    if (billingUpdatesListener != null) {
                                        billingUpdatesListener.onPurchasesFailure(billingResult.getResponseCode(), billingResult.getDebugMessage());
                                    }
                                }
                            }
                        });
            }
        });
    }

    public void quicknessPurchase(String url) {
        Intent intent = new Intent(weakReference.get(), PurchasesActivity.class);
        intent.putExtra("url", url);
        weakReference.get().startActivity(intent);
    }

    /**
     * 发起内购
     *
     * @param skuDetails 商品详情
     */
    private void launchBillingFlow(final SkuDetails skuDetails) {
        // Retrieve a value for "skuDetails" by calling querySkuDetailsAsync().
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                        .setSkuDetails(skuDetails)
                        .build();
                int responseCode = mBillingClient.launchBillingFlow(weakReference.get(), flowParams).getResponseCode();
                LogUtils.e("launchBillingFlow >>> [responseCode:" + responseCode + "]");
            }
        };
        executeServiceRequest(runnable);
    }

    /**
     * 对消耗型商品进行确认购买处理
     */
    public void consumeAsync(final String purchaseToken) {
        executeServiceRequest(new Runnable() {
            @Override
            public void run() {
                ConsumeParams consumeParams =
                        ConsumeParams.newBuilder()
                                .setPurchaseToken(purchaseToken)
                                .build();
                mBillingClient.consumeAsync(consumeParams, new ConsumeResponseListener() {
                    @Override
                    public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String purchaseToken) {
                        if (billingUpdatesListener != null) {
                            billingUpdatesListener.onConsumeFinished(purchaseToken, billingResult);
                        }
                    }
                });
            }
        });
    }

    /**
     * 对非消耗型商品进行确认购买处理
     */
    public void acknowledgePurchase(final String purchaseToken) {
        executeServiceRequest(new Runnable() {
            @Override
            public void run() {
                AcknowledgePurchaseParams acknowledgePurchaseParams =
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchaseToken)
                                .build();
                mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, new AcknowledgePurchaseResponseListener() {
                    @Override
                    public void onAcknowledgePurchaseResponse(@NonNull BillingResult billingResult) {
                        if (billingUpdatesListener != null) {
                            billingUpdatesListener.onAcknowledgePurchaseResponse(billingResult);
                        }
                    }
                });
            }
        });
    }

    /**
     * 连接断开重试策略
     */
    private void executeServiceRequest(Runnable runnable) {
        if (mBillingClient != null) {
            runnable.run();
        } else {
            startServiceConnection(runnable);
        }
    }

    /**
     * 购买交易更新
     */
    @Override
    public void onPurchasesUpdated(BillingResult billingResult, @Nullable List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            if (billingUpdatesListener != null) {
                billingUpdatesListener.onPurchasesUpdated(purchases);
            }
            LogUtils.e("Payment success >>> [code："
                    + billingResult.getResponseCode() + ",message：" + billingResult.getDebugMessage() + "]");
        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            // Handle an error caused by a user cancelling the purchase flow.
            if (billingUpdatesListener != null) {
                billingUpdatesListener.onPurchasesCancel();
            }
            LogUtils.e("Payment cancel >>> [code："
                    + billingResult.getResponseCode() + ",message：" + billingResult.getDebugMessage() + "]");
        } else {
            // Handle any other error codes.
            if (billingUpdatesListener != null) {
                billingUpdatesListener.onPurchasesFailure(billingResult.getResponseCode(), billingResult.getDebugMessage());
            }
            LogUtils.e("Payment failure >>> [code："
                    + billingResult.getResponseCode() + ",message：" + billingResult.getDebugMessage() + "]");
        }
    }

    /**
     * 回收资源
     */
    public void destroy() {
        LogUtils.d("Destroying the manager.");
        if (mBillingClient != null && mBillingClient.isReady()) {
            mBillingClient.endConnection();
            mBillingClient = null;
        }
        if (billingPurchasesReceiver != null) {
            LocalBroadcastManager.getInstance(weakReference.get()).unregisterReceiver(billingPurchasesReceiver);
        }
    }
}