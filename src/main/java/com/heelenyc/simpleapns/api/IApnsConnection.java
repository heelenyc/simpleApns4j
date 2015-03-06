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

import java.io.Closeable;

import com.heelenyc.simpleapns.model.Payload;
import com.heelenyc.simpleapns.model.PushNotification;


public interface IApnsConnection extends Closeable {
	
	/**
	 * 发送notice
	 * @param token
	 * @param payload
	 */
	public void sendNotification(String token, Payload payload);

	/**
	 * 发送notice
	 * @param notification
	 */
	public void sendNotification(PushNotification notification);
	
	/**
	 * 此连接是否可用
	 * @return
	 */
	public boolean isAvailable();
}
