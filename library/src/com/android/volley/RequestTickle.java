/*
 * Copyright (C) 2014 Hari Krishna Dulipudi
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

package com.android.volley;

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.android.volley.error.VolleyError;


/**
 * A request tickle for single requests.
 *
 * Calling {@link #add(Request)} will add the request to request tickle and {@link #start()},
 * will give a {@link NetworkResponse}. The listeners in the request will also be notified.
 */
@SuppressWarnings("rawtypes")
public class RequestTickle {

	private Request mRequest;
	
    /** Cache interface for retrieving and storing response. */
    private final Cache mCache;

    /** Network interface for performing requests. */
    private final Network mNetwork;

    /** Response delivery mechanism. */
    private final ResponseDelivery mDelivery;
 
	private Response<?> response;

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param delivery A ResponseDelivery interface for posting responses and errors
     */
    public RequestTickle(Cache cache, Network network,
            ResponseDelivery delivery) {
        mCache = cache;
        mNetwork = network;
        mDelivery = delivery;
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     */
    public RequestTickle(Cache cache, Network network) {
        this(cache, network,new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    /**
     * Adds a Request to the dispatch queue.
     * @param request The request to service
     * @return The passed-in request
     */
    public <T> Request<T> add(Request<T> request) {
    	this.mRequest = request;
        return request;
    }
    
    /**
     * Cancel the request.
     */
    public void cancel() {
    	if(null == mRequest){
    		return;
    	}
    	mRequest.cancel();
    }

    /**
     * Gets the {@link Cache} instance being used.
     */
    public Cache getCache() {
        return mCache;
    }   

    /**
     * Starts the request and return {@link NetworkResponse} or null.
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH) 
    public NetworkResponse start() {
    	if(null == mRequest){
    		return null;
    	}
        NetworkResponse networkResponse = null;
        try {
            mRequest.addMarker("network-queue-take");

            // If the request was cancelled already, do not perform the
            // network request.
            if (mRequest.isCanceled()) {
                mRequest.finish("network-discard-cancelled");
                return null;
            }

            // Tag the request (if API >= 14)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                TrafficStats.setThreadStatsTag(mRequest.getTrafficStatsTag());
            }
            
            // Perform the network request.
            networkResponse = mNetwork.performRequest(mRequest);
            mRequest.addMarker("network-http-complete");

            // If the server returned 304 AND we delivered a response already,
            // we're done -- don't deliver a second identical response.
            if (networkResponse.notModified && mRequest.hasHadResponseDelivered()) {
                mRequest.finish("not-modified");
                return networkResponse;
            }

            // Parse the response here on the worker thread.
            response = mRequest.parseNetworkResponse(networkResponse);
            mRequest.addMarker("network-parse-complete");

            // Write to cache if applicable.
            // TODO: Only update cache metadata instead of entire record for 304s.
            if (mRequest.shouldCache() && response.cacheEntry != null) {
                mCache.put(mRequest.getCacheKey(), response.cacheEntry);
                mRequest.addMarker("network-cache-written");
            }

            // Post the response back.
            mRequest.markDelivered();
            mDelivery.postResponse(mRequest, response);
        } catch (VolleyError volleyError) {
            parseAndDeliverNetworkError(mRequest, volleyError);
        } catch (Exception e) {
            VolleyLog.e(e, "Unhandled exception %s", e.toString());
            mDelivery.postError(mRequest, new VolleyError(e));
        }

		return networkResponse;
    }
    
    public Response<?> getResponse() {
    	return response;
    }
    
    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }
}