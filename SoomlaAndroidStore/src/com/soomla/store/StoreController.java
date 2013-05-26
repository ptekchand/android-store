/*
 * Copyright (C) 2012 Soomla Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soomla.store;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.soomla.billing.BillingService;
import com.soomla.billing.Consts;
import com.soomla.billing.PurchaseObserver;
import com.soomla.billing.ResponseHandler;
import com.soomla.billing.util.IabHelper;
import com.soomla.billing.util.IabResult;
import com.soomla.billing.util.Inventory;
import com.soomla.billing.util.Purchase;
import com.soomla.store.data.ObscuredSharedPreferences;
import com.soomla.store.data.StoreInfo;
import com.soomla.store.domain.GoogleMarketItem;
import com.soomla.store.domain.NonConsumableItem;
import com.soomla.store.domain.PurchasableVirtualItem;
import com.soomla.store.domain.virtualCurrencies.VirtualCurrencyPack;
import com.soomla.store.domain.virtualGoods.VirtualGood;
import com.soomla.store.events.*;
import com.soomla.store.exceptions.VirtualItemNotFoundException;
import com.soomla.store.purchaseTypes.PurchaseType;
import com.soomla.store.purchaseTypes.PurchaseWithMarket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class holds the basic assets needed to operate the Store.
 * You can use it to purchase products from Google Play.
 *
 * This is the only class you need to initialize in order to use the SOOMLA SDK.
 *
 * In addition to initializing this class, you'll also have to call
 * {@link StoreController#storeOpening(android.app.Activity)} and
 * {@link com.soomla.store.StoreController#storeClosing()} when you open the store window or close it. These two
 * calls initializes important components that support billing and storage information (see implementation below).
 * IMPORTANT: if you use the SOOMLA's storefront, then DON'T call these 2 functions.
 *
 */
public class StoreController extends PurchaseObserver {

    /**
     * This initializer also initializes {@link StoreInfo}.
     * @param storeAssets is the definition of your application specific assets.
     * @param publicKey is the public key given to you from Google.
     * @param customSecret is your encryption secret (it's used to encrypt your data in the database)
     */
    public void initialize(IStoreAssets storeAssets,
                          String publicKey,
                          String customSecret){

        if (mInitialized) {
            StoreUtils.LogError(TAG, "StoreController is already initialized. You can't initialize it twice!");
            return;
        }

        SharedPreferences prefs = new ObscuredSharedPreferences(SoomlaApp.getAppContext().getSharedPreferences(StoreConfig.PREFS_NAME, Context.MODE_PRIVATE));
        SharedPreferences.Editor edit = prefs.edit();
        if (publicKey != null && !publicKey.isEmpty()) {
            edit.putString(StoreConfig.PUBLIC_KEY, publicKey);
        } else if (prefs.getString(StoreConfig.PUBLIC_KEY, "").isEmpty()) {
            StoreUtils.LogError(TAG, "publicKey is null or empty. can't initialize store !!");
            return;
        }
        if (customSecret != null && !customSecret.isEmpty()) {
            edit.putString(StoreConfig.CUSTOM_SEC, customSecret);
        } else if (prefs.getString(StoreConfig.CUSTOM_SEC, "").isEmpty()) {
            StoreUtils.LogError(TAG, "customSecret is null or empty. can't initialize store !!");
            return;
        }
        edit.putInt("SA_VER_NEW", storeAssets.getVersion());
        edit.commit();

        if (storeAssets != null) {
            StoreInfo.setStoreAssets(storeAssets);
        }

        // Billing service (IAB v3 helper) will be started on storeOpening when mActivity is set.
        //if (startBillingService()) {
            // We're not restoring transactions automatically anymore.
            // Call storeController.getInstance().restoreTransactions() when you want to do that.
//            tryRestoreTransactions();
        //}

        mInitialized = true;
    }

    /**
     * Start a purchase process with Google Play.
     *
     * @param googleMarketItem is the item to purchase. This item has to be defined EXACTLY the same in Google Play.
     * @param payload a payload to get back when this purchase is finished.
     */
    public boolean buyWithGooglePlay(GoogleMarketItem googleMarketItem, String payload) {
        if (!checkInit()) return false;

        SharedPreferences prefs = new ObscuredSharedPreferences(SoomlaApp.getAppContext().getSharedPreferences(StoreConfig.PREFS_NAME, Context.MODE_PRIVATE));
        String publicKey = prefs.getString(StoreConfig.PUBLIC_KEY, "");
        if (publicKey.isEmpty() || publicKey.equals("[YOUR PUBLIC KEY FROM GOOGLE PLAY]")) {
            StoreUtils.LogError(TAG, "You didn't provide a public key! You can't make purchases.");
            return false;
        }

        // IAB v3
        // (arbitrary) request code for the purchase flow. FIXME
        final int RC_REQUEST = 10001;
        mIabHelper.launchPurchaseFlow(mActivity, googleMarketItem.getProductId(), RC_REQUEST, 
                mPurchaseFinishedListener, payload);
        // TODO: If we ever end up supporting subscription items:
        //mIabHelper.launchPurchaseFlow(this, googleMarketItem.getProductId(), 
        //		IabHelper.ITEM_TYPE_SUBS, RC_REQUEST, mPurchaseFinishedListener, payload);        

        // IAB v2
        //if (!mBillingService.requestPurchase(googleMarketItem.getProductId(), Consts.ITEM_TYPE_INAPP, payload)){
        //    return false;
        //}

        try {
            BusProvider.getInstance().post(new PlayPurchaseStartedEvent(StoreInfo.getPurchasableItem(googleMarketItem.getProductId())));
        } catch (VirtualItemNotFoundException e) {
            StoreUtils.LogError(TAG, "Couldn't find a purchasable item with productId: " + googleMarketItem.getProductId());
        }
        return true;
    }

    // Callback for when a purchase is finished
    IabHelper.OnIabPurchaseFinishedListener mPurchaseFinishedListener = new IabHelper.OnIabPurchaseFinishedListener() {
        public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
        	String productId = purchase.getSku();
        	StoreUtils.LogDebug(TAG, "Purchase finished: " + result + ", purchase: " + purchase);
            if (result.isFailure()) {
            	StoreUtils.LogError(TAG, "Error purchasing: " + result);
                return;
            }
            if (!verifyDeveloperPayload(purchase)) {
            	StoreUtils.LogError(TAG, "Error purchasing. Authenticity verification failed.");
                return;
            }

            StoreUtils.LogDebug(TAG, "Purchase successful.");
            Consts.PurchaseState pState = Consts.PurchaseState.valueOf(purchase.getPurchaseState());

           	onPurchaseStateChange(pState, productId, purchase.getPurchaseTime(), purchase.getDeveloperPayload());
            
            // TODO: If we ever support consumables on Google Play. Something on these lines would be needed.
			//PurchasableVirtualItem pvi = StoreInfo.getPurchasableItem(productId);
            //if (pvi.getPurchaseType() instanceof PurchaseWithMarketConsumable ) {
            //	StoreUtils.LogDebug(TAG, "Purchase is of a consumable. Starting consumption.");
            //    mIabHelper.consumeAsync(purchase, mConsumeFinishedListener);
            //}
        }
    };

    /**
     * Call this function when you open the actual store window
     * @param activity is the activity being opened (or the activity that contains the store)/
     */
    public void storeOpening(Activity activity){
        if (!checkInit()) return;

        mLock.lock();
        if (mStoreOpen) {
            StoreUtils.LogError(TAG, "Store is already open !");
            mLock.unlock();
            return;
        }

        /* Initialize StoreInfo from database in case any changes were done to it while the store was closed */
        StoreInfo.initializeFromDB();

        // IAB v3
        mActivity = activity;
        SharedPreferences prefs = new ObscuredSharedPreferences(SoomlaApp.getAppContext().getSharedPreferences(StoreConfig.PREFS_NAME, Context.MODE_PRIVATE));
        String publicKey = prefs.getString(StoreConfig.PUBLIC_KEY, "");
        mIabHelper = new IabHelper(mActivity, publicKey);
        if(isTestMode()) {
        	mIabHelper.enableDebugLogging(true);
        }
        /* Billing */
        startBillingService();

        BusProvider.getInstance().post(new OpeningStoreEvent());

        mStoreOpen = true;
        mLock.unlock();
    }

    /**
     * Call this function when you close the actual store window.
     */
    public void storeClosing(){
        if (!mStoreOpen) return;

        mStoreOpen = false;

        BusProvider.getInstance().post(new ClosingStoreEvent());

        mActivity = null;
        stopBillingService();
//        ResponseHandler.unregister(this);
    }

    /**
     * Initiate the restoreTransactions process
     * Potentially @deprecated with IAB v3.
     */
    public void restoreTransactions() {
        if (!checkInit()) return;

        StoreUtils.LogDebug(TAG, "Sending restore transaction request");
        mBillingService.restoreTransactions();

        BusProvider.getInstance().post(new RestoreTransactionsStartedEvent());
    }

    /**
     * Answers the question: "Were transactions already restored for this game?"
     */
    public boolean transactionsAlreadyRestored() {
        SharedPreferences prefs = new ObscuredSharedPreferences(SoomlaApp.getAppContext().getSharedPreferences(StoreConfig.PREFS_NAME, Context.MODE_PRIVATE));
        return prefs.getBoolean("RESTORED", false);
    }


    /** PurchaseObserver overridden functions**/

    /**
     * docs in {@link PurchaseObserver#onBillingSupported(boolean supported, String type)}.
     * Potentially @deprecated with IAB v3. Re-implemented separately.
     */
    @Override
    public void onBillingSupported(boolean supported, String type) {
        if (type == null || type.equals(Consts.ITEM_TYPE_INAPP)) {
            if (supported) {
                StoreUtils.LogDebug(TAG, "billing is supported !");

                BusProvider.getInstance().post(new BillingSupportedEvent());
            } else {
                // purchase is not supported. just send a message to JS to disable the "get more ..." button.

                StoreUtils.LogDebug(TAG, "billing is not supported !");

                BusProvider.getInstance().post(new BillingNotSupportedEvent());
            }
        } else if (type.equals(Consts.ITEM_TYPE_SUBSCRIPTION)) {
            // subscription is not supported
            // Soomla doesn't support subscriptions yet. doing nothing here ...
        } else {
            // subscription is not supported
            // Soomla doesn't support subscriptions yet. doing nothing here ...
        }
    }

    /**
     * docs in {@link PurchaseObserver#onPurchaseStateChange(com.soomla.billing.Consts.PurchaseState, String, long, String)}.
     * Potentially @deprecated with IAB v3. Will be re-implemented separately.
     */
    @Override
    public void onPurchaseStateChange(Consts.PurchaseState purchaseState, String productId, long purchaseTime, String developerPayload) {
        try {
            PurchasableVirtualItem purchasableVirtualItem = StoreInfo.getPurchasableItem(productId);

            BusProvider.getInstance().post(new PlayPurchaseEvent(purchasableVirtualItem, developerPayload));

            if (purchaseState == Consts.PurchaseState.PURCHASED) {
                purchasableVirtualItem.give(1);
            }

            if (purchaseState == Consts.PurchaseState.REFUNDED){
                if (!StoreConfig.friendlyRefunds) {
                    purchasableVirtualItem.take(1);
                }
            }

            BusProvider.getInstance().post(new ItemPurchasedEvent(purchasableVirtualItem));
        } catch (VirtualItemNotFoundException e) {
            StoreUtils.LogError(TAG, "ERROR : Couldn't find the " + purchaseState.name() +
                    " VirtualCurrencyPack OR GoogleMarketItem  with productId: " + productId +
                    ". It's unexpected so an unexpected error is being emitted.");
            BusProvider.getInstance().post(new UnexpectedStoreErrorEvent());
        }
    }

    /**
     * docs in {@link PurchaseObserver#onRequestPurchaseResponse(com.soomla.billing.BillingService.RequestPurchase, com.soomla.billing.Consts.ResponseCode)}.
     * Potentially @deprecated with IAB v3. Will be re-implemented separately.
     */
    @Override
    public void onRequestPurchaseResponse(BillingService.RequestPurchase request, Consts.ResponseCode responseCode) {
        if (responseCode == Consts.ResponseCode.RESULT_OK) {
            // purchase was sent to server
        } else if (responseCode == Consts.ResponseCode.RESULT_USER_CANCELED) {

            try {
                BusProvider.getInstance().post(new PlayPurchaseCancelledEvent(StoreInfo.getPurchasableItem(request.mProductId)));
            } catch (VirtualItemNotFoundException e) {
                StoreUtils.LogError(TAG, "ERROR : Couldn't find the CANCELLED VirtualCurrencyPack OR GoogleMarketItem  with productId: " + request.mProductId +
                        ". It's unexpected so an unexpected error is being emitted.");
                BusProvider.getInstance().post(new UnexpectedStoreErrorEvent());
            }

        } else {
            // purchase failed !

            BusProvider.getInstance().post(new UnexpectedStoreErrorEvent());
            StoreUtils.LogError(TAG, "ERROR : Purchase failed for productId: " + request.mProductId);
        }
    }

    /**
     * docs in {@link PurchaseObserver#onRestoreTransactionsResponse(com.soomla.billing.BillingService.RestoreTransactions, com.soomla.billing.Consts.ResponseCode)}.
     * Potentially @deprecated with IAB v3. Re-implemented separately.
     */
    @Override
    public void onRestoreTransactionsResponse(BillingService.RestoreTransactions request, Consts.ResponseCode responseCode) {

        if (responseCode == Consts.ResponseCode.RESULT_OK) {
            StoreUtils.LogDebug(TAG, "RestoreTransactions succeeded");

            SharedPreferences prefs = new ObscuredSharedPreferences(SoomlaApp.getAppContext().getSharedPreferences(StoreConfig.PREFS_NAME, Context.MODE_PRIVATE));
            SharedPreferences.Editor edit = prefs.edit();

            edit.putBoolean("RESTORED", true);
            edit.commit();

            BusProvider.getInstance().post(new RestoreTransactionsEvent(true));
        } else {
            StoreUtils.LogDebug(TAG, "RestoreTransactions error: " + responseCode);

            BusProvider.getInstance().post(new RestoreTransactionsEvent(false));
        }

        // we're stopping the billing service only if the store was not opened while the request was sent
        if (!mStoreOpen) {
            stopBillingService();
        }
    }

    public boolean isTestMode() {
        return mTestMode;
    }

    public void setTestMode(boolean mTestMode) {
        this.mTestMode = mTestMode;
    }

    /** Private methods **/

    private boolean checkInit() {
        if (!mInitialized) {
            StoreUtils.LogDebug(TAG, "You can't perform any of StoreController's actions before it was initialized. Initialize it once when your game loads.");
            return false;
        }
        return true;
    }

    // TODO: check all calls, to change code that runs based on the boolean result
    // to run after the async response has been received.
    private boolean startBillingService() {
        mLock.lock();
        final boolean response = true;
        /** IAB v3 WARNING this is async. **/
        mIabHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
            public void onIabSetupFinished(IabResult result) {
            	StoreUtils.LogDebug(TAG, "Setup finished.");

                if (!result.isSuccess()) {
                    // There was a problem.
                	StoreUtils.LogError(TAG, "Problem setting up in-app billing: " + result);
                    BusProvider.getInstance().post(new BillingNotSupportedEvent());
                	
                	mLock.unlock();
                	//response = false;
                    return; // TODO/TEST: How can I return a false signal to the outer caller here?
                }

                // Hooray, IAB is fully set up. Now, let's get an inventory of stuff we own.
                BusProvider.getInstance().post(new BillingSupportedEvent());
                StoreUtils.LogDebug(TAG, "Setup successful. Querying inventory.");
                //StoreUtils.LogDebug(TAG, "Sending restore transaction request");
                // Consider renaming Event to QueryInventoryAsyncStartedEvent
                BusProvider.getInstance().post(new RestoreTransactionsStartedEvent());
                mIabHelper.queryInventoryAsync(mGotInventoryListener);
                // TODO: How can I return a true signal to the outer caller here?
                //response = true;
            }
        });

        mLock.unlock();
        return response;
    }

    private void stopBillingService() {
        mLock.lock();
        // IAB v3
        if(mIabHelper!=null) {
        	mIabHelper.dispose();
        	mIabHelper = null;
        }
        mLock.unlock();
    }
    // If really required, IabHelper::mService could be returned?
    // No calls found in cocos2dx-store sample usage. deprecate and return getBillingHelper
    public BillingService getBillingService() {
        return mBillingService;
    }

    // Listener that's called when we finish querying the items and subscriptions we own
    IabHelper.QueryInventoryFinishedListener mGotInventoryListener = new IabHelper.QueryInventoryFinishedListener() {
        public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
        	// Essentially the replacement of onRestoreTransactionsResponse
        	StoreUtils.LogDebug(TAG, "Query inventory finished.");
            if (result.isFailure()) {
            	StoreUtils.LogError(TAG, "Failed to query inventory: " + result);
                StoreUtils.LogDebug(TAG, "RestoreTransactions error: " + result.getResponse());

                BusProvider.getInstance().post(new RestoreTransactionsEvent(false));
                //return;
            } else {
                StoreUtils.LogDebug(TAG, "Query inventory was successful.");
                // IAB v2 note:
                // "The RESTORE_TRANSACTIONS request type also triggers a PURCHASE_STATE_CHANGED broadcast intent"

                // Check the purchase status of items which can be purchased from the market
                // Find all PurchaseWithMarket items.
                //for each of currencyPacks, goods, nonConsumables ...
                List<VirtualCurrencyPack> currencyPacks = StoreInfo.getCurrencyPacks();
                List<String> purchasableProductIDs = new ArrayList<String>();
                for(VirtualCurrencyPack acPack: currencyPacks) {
                	if(acPack.getPurchaseType() instanceof PurchaseWithMarket) {
                		PurchaseWithMarket pwm = (PurchaseWithMarket) acPack.getPurchaseType();
                		purchasableProductIDs.add(pwm.getGoogleMarketItem().getProductId());
                	}
                }
                List<VirtualGood> goods = StoreInfo.getGoods();
                for(VirtualGood aGood: goods) {
                	if(aGood.getPurchaseType() instanceof PurchaseWithMarket) {
                		PurchaseWithMarket pwm = (PurchaseWithMarket) aGood.getPurchaseType();
                		purchasableProductIDs.add(pwm.getGoogleMarketItem().getProductId());
                	}
                }
                List<NonConsumableItem> nonConsumables = StoreInfo.getNonConsumableItems();
                for(NonConsumableItem anc: nonConsumables) {
                	if(anc.getPurchaseType() instanceof PurchaseWithMarket) {
                		PurchaseWithMarket pwm = (PurchaseWithMarket) anc.getPurchaseType();
                		purchasableProductIDs.add(pwm.getGoogleMarketItem().getProductId());
                	}
                }

                /*
                 * Check for items we own. Notice that for each purchase, we check
                 * the developer payload to see if it's correct! See
                 * verifyDeveloperPayload().
                 */
                for(String productId: purchasableProductIDs) {
                    Purchase purchase = inventory.getPurchase(productId);
                    boolean isPurchased = (purchase != null && verifyDeveloperPayload(purchase));
                	long purchaseTimeHack = 0;
                	String developerPayloadHack = "onPurchaseStateChange will be replaced";
                    if(isPurchased) {
                    	onPurchaseStateChange(Consts.PurchaseState.PURCHASED, productId, purchaseTimeHack, developerPayloadHack);
                    } else {
                    	// Note, this is only for MANAGED (+ non-consumable) products.
                    	// If we call fire the purchase state changed event for Unmanaged items,
                    	// They may get removed from the inventory unnecessarily.
                    	onPurchaseStateChange(Consts.PurchaseState.CANCELED, productId, purchaseTimeHack, developerPayloadHack);
                    }
                    
                    // TODO: If any of these are consumable on Google Play, we need to tell Google to consume them
                    // mIabHelper.consumeAsync(inventory.getPurchase(productId), mConsumeFinishedListener);
                    // and in mConsumeFinishedListener, save it in the SOOMLA DB.
                    // IAB v2 did not support consumables. They were Unmanaged items. To add this
                    // support, we may need to change more logic in SOOMLA.
                }
                // TODO: Possibly call PurchaseStateChangedEvent for all mIabHelper.Inventory.getAllOwnedSkus or getAllPurchases ?
                // StoreInventory.initializeWithStoreAssets probably gets called around here as well.
                
                StoreUtils.LogDebug(TAG, "RestoreTransactions succeeded");

                SharedPreferences prefs = new ObscuredSharedPreferences(SoomlaApp.getAppContext().getSharedPreferences(StoreConfig.PREFS_NAME, Context.MODE_PRIVATE));
                SharedPreferences.Editor edit = prefs.edit();

                edit.putBoolean("RESTORED", true);
                edit.commit();

                BusProvider.getInstance().post(new RestoreTransactionsEvent(true));
            }

            // we're stopping the billing service only if the store was not opened while the request was sent
            if (!mStoreOpen) {
                stopBillingService();
            }
        }
    };
    
    // TODO: If we end up supporting consumable items on Google Play, we'll need to implement:
    // Called when consumption is complete
    //IabHelper.OnConsumeFinishedListener mConsumeFinishedListener = new IabHelper.OnConsumeFinishedListener()
    
    
    /** Verifies the developer payload of a purchase. */
    boolean verifyDeveloperPayload(Purchase p) {
        String payload = p.getDeveloperPayload();
        /*
         * TODO: verify that the developer payload of the purchase is correct. It will be
         * the same one that you sent when initiating the purchase.
         * 
         * WARNING: Locally generating a random string when starting a purchase and 
         * verifying it here might seem like a good approach, but this will fail in the 
         * case where the user purchases an item on one device and then uses your app on 
         * a different device, because on the other device you will not have access to the
         * random string you originally generated.
         *
         * So a good developer payload has these characteristics:
         * 
         * 1. If two different users purchase an item, the payload is different between them,
         *    so that one user's purchase can't be replayed to another user.
         * 
         * 2. The payload must be such that you can verify it even when the app wasn't the
         *    one who initiated the purchase flow (so that items purchased by the user on 
         *    one device work on other devices owned by the user).
         * 
         * Using your own server to store and verify developer payloads across app
         * installations is recommended.
         */
        
        return true;
    }

    /**
     * Handles an activity result that's part of the purchase flow in in-app billing.
     * This method MUST be called from the UI thread of the Activity.
     * {@link com.soomla.billing.util.IabHelper@handleActivityResult}
     * Activities calling storeOpening need to override onActivityResult
     * and call handleActivityResult on this.
     * 
     * @param requestCode The requestCode as you received it.
     * @param resultCode The resultCode as you received it.
     * @param data The data (Intent) as you received it.
     * @return Returns true if the result was related to a purchase flow and was handled;
     *     false if the result was not related to a purchase, in which case you should
     *     handle it normally.
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
    	if(mIabHelper!=null /*&& mStoreOpen*/) {
    		return mIabHelper.handleActivityResult(requestCode, resultCode, data);
    	}
    	return false;
    }

    /**
     * In case activities need to access the helper.
     * @return
     */
    public IabHelper getIabHelper() {
    	return mIabHelper;
    }

    /** Singleton **/

    private static StoreController sInstance = null;

    public static StoreController getInstance(){
        if (sInstance == null){
            sInstance = new StoreController();
        }

        return sInstance;
    }

    private StoreController() {
    }

    // TODO: Remove IAB v2 stuff. No longer need to listen on broadcast intents etc.
    // Initializes IabHelper and keep it around. Implement the callbacks/delegates for
    // IAB v3
    IabHelper mIabHelper;
    private Activity mActivity;
    
    /** Private Members**/

    private static final String TAG       = "SOOMLA StoreController";

    private boolean mStoreOpen            = false;
    private boolean mInitialized          = false;
    private boolean mTestMode             = false;

    private BillingService mBillingService;
    private Lock    mLock = new ReentrantLock();
}
