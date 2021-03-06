package com.alicp.jetcache;

import com.alicp.jetcache.embedded.AbstractEmbeddedCache;
import com.alicp.jetcache.external.AbstractExternalCache;
import com.alicp.jetcache.support.JetCacheExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Created on 2017/5/25.
 *
 * @author <a href="mailto:yeli.hl@taobao.com">huangli</a>
 */
public class RefreshCache<K, V> extends LoadingCache<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(RefreshCache.class);

    private ConcurrentHashMap<Object, RefreshTask> taskMap = new ConcurrentHashMap<>();

    public RefreshCache(Cache cache) {
        super(cache);
    }

    @Override
    public void close() {
        List<RefreshTask> tasks = new ArrayList<>();
        taskMap.values().forEach(task -> tasks.add(task));
        tasks.forEach(task -> {
            taskMap.remove(task.taskId);
            task.future.cancel(false);
        });
    }


    private boolean hasLoader() {
        return config.getLoader() != null;
    }

    private Cache concreteCache() {
        Cache c = getTargetCache();
        while (true) {
            if (c instanceof ProxyCache) {
                c = ((ProxyCache) c).getTargetCache();
            } else if (c instanceof MultiLevelCache) {
                Cache[] caches = ((MultiLevelCache) c).caches();
                c = caches[caches.length - 1];
            } else {
                return c;
            }
        }
    }

    private Object getTaskId(K key) {
        Cache c = concreteCache();
        if (c instanceof AbstractEmbeddedCache) {
            return ((AbstractEmbeddedCache) c).buildKey(key);
        } else if (c instanceof AbstractExternalCache) {
            byte[] bs = ((AbstractExternalCache) c).buildKey(key);
            return ByteBuffer.wrap(bs);
        } else {
            logger.error("can't getTaskId from " + c.getClass());
            return null;
        }
    }

    private void addTaskOrUpdateLastAccessTime(Object taskId, long refreshMillis, K key) {
        if (refreshMillis > 0) {
            RefreshTask refreshTask = taskMap.computeIfAbsent(taskId, tid -> {
                RefreshTask task = new RefreshTask(taskId, key);
                task.lastAccessTime = System.currentTimeMillis();
                ScheduledFuture<?> future = JetCacheExecutor.heavyIOExecutor().scheduleWithFixedDelay(
                        task, refreshMillis, refreshMillis, TimeUnit.MILLISECONDS);
                task.future = future;
                return task;
            });
            refreshTask.lastAccessTime = System.currentTimeMillis();
        }
    }

    @Override
    public CacheGetResult<V> GET(K key) {
        if (config.getRefreshPolicy() != null && hasLoader()) {
            addTaskOrUpdateLastAccessTime(getTaskId(key),
                    config.getRefreshPolicy().getRefreshMillis(),
                    key);
        }
        return cache.GET(key);
    }

    @Override
    public MultiGetResult<K, V> GET_ALL(Set<? extends K> keys) {
        if (config.getRefreshPolicy() != null && hasLoader()) {
            for (K key : keys) {
                addTaskOrUpdateLastAccessTime(getTaskId(key),
                        config.getRefreshPolicy().getRefreshMillis(),
                        key);
            }
        }
        return cache.GET_ALL(keys);
    }

    class RefreshTask implements Runnable {
        private Object taskId;
        private K key;
        private long lastAccessTime;
        private ScheduledFuture future;

        RefreshTask(Object taskId, K key) {
            this.taskId = taskId;
            this.key = key;
        }

        private void cancel() {
            future.cancel(false);
            taskMap.remove(taskId);
        }

        private void load() {
            try {
                CacheLoader<K, V> loader = config.getLoader();
                loader = CacheUtil.createProxyLoader(cache, loader, eventConsumer);
                V v = loader.load(key);
                cache.PUT(key, v);
            } catch (Throwable e) {
                throw new CacheInvokeException(e);
            }
        }

        @Override
        public void run() {
            try {
                if (config.getRefreshPolicy() == null || config.getLoader() == null) {
                    cancel();
                    return;
                }
                long now = System.currentTimeMillis();
                long stopRefreshAfterLastAccessMillis = config.getRefreshPolicy().getStopRefreshAfterLastAccessMillis();
                if (stopRefreshAfterLastAccessMillis > 0) {
                    if (lastAccessTime + stopRefreshAfterLastAccessMillis < now) {
                        cancel();
                        return;
                    }
                }
                Cache c = concreteCache();
                if (c instanceof AbstractExternalCache) {
                    final byte[] suffix = "_#RL#".getBytes();
                    byte[] newKey = ((AbstractExternalCache) c).buildKey(key);
                    byte[] lockKey = Arrays.copyOf(newKey, newKey.length + suffix.length);
                    System.arraycopy(suffix, 0, lockKey, newKey.length, suffix.length);
                    long loadTimeOut = RefreshCache.this.config.getRefreshPolicy().getRefreshLockTimeoutMillis();
                    Method method = cache.getClass().getMethod("tryLockAndRun",
                            Object.class, long.class, TimeUnit.class, Runnable.class);
                    Runnable r = this::load;
                    // AbstractExternalCache buildKey method will not convert byte[]
                    method.invoke(cache, lockKey, loadTimeOut, TimeUnit.MILLISECONDS, r);
                } else {
                    load();
                }
            } catch (InvocationTargetException e) {
                logger.error("load key error: key=" + key, e.getTargetException());
            } catch (Throwable e) {
                logger.error("load key error: key=" + key, e);
            }
        }
    }
}
