package com.jungle.apps.photos.module.favorite.data.pic;

import android.content.Context;
import android.text.TextUtils;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.jungle.apps.photos.base.component.AppUtils;
import com.jungle.apps.photos.base.manager.FileDownloadRequest;
import com.jungle.apps.photos.module.category.data.manager.CategoryManager;
import com.jungle.apps.photos.module.category.provider.CategoryContentProvider;
import com.jungle.apps.photos.module.category.provider.CategoryProviderManager;
import com.jungle.apps.photos.module.category.provider.FavoriteContentProvider;
import com.jungle.base.app.AppCore;
import com.jungle.base.manager.AppManager;
import com.jungle.base.manager.ThreadManager;
import com.jungle.base.utils.FileUtils;
import com.jungle.simpleorm.supporter.ORMSupporter;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public abstract class FavoriteManager implements AppManager {

    public static FavoriteManager getInstance() {
        return AppCore.getInstance().getManager(GlobalFavoriteManager.class);
    }


    public static interface OnFavoriteListener {
        void onFavoriteSuccess(String id);

        void onFavoriteFailed(String id);

        void onFavoriteCanceled(String id);
    }


    public static interface OnFavoriteEventListener {
        void onAddFavorite(String id);

        void onCancelFavorite(String id);

        void onClearFavorite();

        void onCountUpdated();
    }


    private int mProviderId = CategoryProviderManager.INVALID_PROVIDER_ID;
    protected List<WeakReference<OnFavoriteEventListener>> mFavoriteEventList = new LinkedList<>();
    protected Map<Long, FavoriteEntity> mCachedFavoriteEntities = new HashMap<>();


    public abstract void synchronizeList();

    protected abstract ORMSupporter getORMSupporter();

    public abstract void doAddFavorite(FavoriteEntity entity);

    public abstract boolean doCancelFavorite(String id);


    @Override
    public void onCreate() {
    }

    @Override
    public void onTerminate() {
    }

    public void addFavoriteEventListener(OnFavoriteEventListener l) {
        boolean found = false;

        for (Iterator<WeakReference<OnFavoriteEventListener>> iterator = mFavoriteEventList.iterator();
             iterator.hasNext(); ) {
            OnFavoriteEventListener listener = iterator.next().get();
            if (listener == null) {
                iterator.remove();
            } else if (listener == l) {
                found = true;
            }
        }

        if (!found) {
            mFavoriteEventList.add(new WeakReference<OnFavoriteEventListener>(l));
        }
    }

    public void removeFavoriteEventListener(OnFavoriteEventListener l) {
        for (Iterator<WeakReference<OnFavoriteEventListener>> iterator = mFavoriteEventList.iterator();
             iterator.hasNext(); ) {
            OnFavoriteEventListener listener = iterator.next().get();
            if (listener == null || listener == l) {
                iterator.remove();
            }
        }
    }

    public void synchronizeEntity(FavoriteEntity entity) {
        getORMSupporter().update(entity);
        mCachedFavoriteEntities.put(entity.mGuid, entity);
    }

    protected void removeEntity(FavoriteEntity entity) {
        getORMSupporter().remove(FavoriteEntity.class,
                FavoriteEntity.idEqualCondition(entity.mId));

        for (long guid : mCachedFavoriteEntities.keySet()) {
            FavoriteEntity cachedEntity = mCachedFavoriteEntities.get(guid);
            if (TextUtils.equals(cachedEntity.mId, entity.mId)) {
                mCachedFavoriteEntities.remove(guid);
                break;
            }
        }
    }

    public FavoriteEntity getFavoriteEntity(int position) {
        FavoriteEntity entity = mCachedFavoriteEntities.get((long) position);
        if (entity != null) {
            return entity;
        }

        entity = getORMSupporter().queryByPosition(
                FavoriteEntity.class, position);
        mCachedFavoriteEntities.put((long) position, entity);
        return entity;
    }

    public FavoriteEntity getFavoriteEntity(String id) {
        List<FavoriteEntity> list = getORMSupporter().queryByCondition(
                FavoriteEntity.class, FavoriteEntity.idEqualCondition(id), null);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    public final boolean isFavorite(String id) {
        if (TextUtils.isEmpty(id)) {
            return false;
        }

        return 0 < getORMSupporter().queryCount(
                FavoriteEntity.class, FavoriteEntity.idEqualCondition(id));
    }

    public final int getFavoritesCount() {
        return getORMSupporter().queryCount(FavoriteEntity.class);
    }

    public void clearFavorites() {
        getORMSupporter().removeAll(FavoriteEntity.class);
        notifyClearFavorite();
    }

    public boolean cancelFavorite(String id) {
        return !TextUtils.isEmpty(id) && doCancelFavorite(id);
    }

    protected boolean doCancelFavoriteInternal(String id) {
        ORMSupporter supporter = getORMSupporter();
        FavoriteEntity entity = getFavoriteEntity(id);
        if (entity != null) {
            FileUtils.cleanDirectory(entity.mLocalPath);
        } else {
            entity = new FavoriteEntity();
            entity.mId = id;
        }

        removeEntity(entity);
        return true;
    }

    public void addFavorite(
            Context context,
            final CategoryManager.CategoryItem item,
            final OnFavoriteListener listener) {

        if (TextUtils.isEmpty(item.mId) || TextUtils.isEmpty(item.mSrcUrl)) {
            return;
        }

        if (AppUtils.isDownloadWhenFavPic()) {
            String favDir = AppUtils.getFavouritePicFile(item.mId);
            FileDownloadRequest request = new FileDownloadRequest(item.mSrcUrl, favDir,
                    new Response.Listener<String>() {
                        @Override
                        public void onResponse(String response) {
                            doAddFavorite(item, response);
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            listener.onFavoriteFailed(item.mId);
                        }
                    });
        } else {
            doAddFavorite(item, null);
        }
    }

    public final void doAddFavorite(CategoryManager.CategoryItem item, String localPath) {
        FavoriteEntity entity = convertCategoryItem(item, localPath);
        doAddFavorite(entity);
    }

    protected static FavoriteEntity convertCategoryItem(
            CategoryManager.CategoryItem item, String localPath) {

        FavoriteEntity entity = new FavoriteEntity();
        entity.mId = item.mId;
        entity.mTitle = item.mTitle;
        entity.mSrcUrl = item.mSrcUrl;
        entity.mFavTime = System.currentTimeMillis();
        entity.mLocalPath = localPath;

        return entity;
    }

    public int getProviderId() {
        if (mProviderId == CategoryProviderManager.INVALID_PROVIDER_ID) {
            CategoryManager.CategoryInfo info = generateCategoryInfo();
            CategoryContentProvider provider = new FavoriteContentProvider(info, this);
            mProviderId = CategoryProviderManager.getInstance().addProvider(provider);
        }

        return mProviderId;
    }

    private CategoryManager.CategoryInfo generateCategoryInfo() {
        CategoryManager.CategoryInfo info =
                new CategoryManager.CategoryInfo(null, null);
        info.mCategoryType = CategoryManager.CategoryType.Favorited;
        return info;
    }

    private static interface NotifyHandler {
        void handleNotify(OnFavoriteEventListener listener);
    }


    private void notifyInternal(final NotifyHandler handler) {
        ThreadManager.getInstance().executeOnUIHandler(new Runnable() {
            @Override
            public void run() {
                for (Iterator<WeakReference<OnFavoriteEventListener>> iterator = mFavoriteEventList.iterator();
                     iterator.hasNext(); ) {
                    OnFavoriteEventListener listener = iterator.next().get();
                    if (listener != null) {
                        handler.handleNotify(listener);
                    } else {
                        iterator.remove();
                    }
                }
            }
        });
    }

    protected void notifyAddFavorite(final String id) {
        notifyInternal(new NotifyHandler() {
            @Override
            public void handleNotify(OnFavoriteEventListener listener) {
                listener.onAddFavorite(id);
            }
        });
    }

    protected void notifyCancelFavorite(final String id) {
        notifyInternal(new NotifyHandler() {
            @Override
            public void handleNotify(OnFavoriteEventListener listener) {
                listener.onCancelFavorite(id);
            }
        });
    }

    protected void notifyClearFavorite() {
        notifyInternal(new NotifyHandler() {
            @Override
            public void handleNotify(OnFavoriteEventListener listener) {
                listener.onClearFavorite();
            }
        });
    }

    protected void notifyCountUpdated() {
        notifyInternal(new NotifyHandler() {
            @Override
            public void handleNotify(OnFavoriteEventListener listener) {
                listener.onCountUpdated();
            }
        });
    }
}