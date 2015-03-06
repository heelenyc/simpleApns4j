/*
 * Copyright 2013 DiscoveryBay Inc.
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
package com.heelenyc.simpleapns.api;

import java.util.List;

import com.heelenyc.simpleapns.model.Feedback;
import com.heelenyc.simpleapns.model.Payload;
import com.heelenyc.simpleapns.model.PushNotification;

/**
 * @author yicheng
 * @since 2015年3月6日
 *
 */
public interface IApnsService {
	/**
	 * @param token  
	 * @param payload
	 */
	public void sendNotification(String token, Payload payload);
	/**
	 * @param notification
	 */
	public void sendNotification(PushNotification notification);
	
	public void shutdown();

	public List<Feedback> getFeedbacks();
}