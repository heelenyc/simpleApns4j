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
package com.heelenyc.apns4j;

import java.io.IOException;

import com.heelenyc.apns4j.model.Payload;
import com.heelenyc.apns4j.model.PushNotification;

public interface IApnsConnection  {
	
	public void sendNotification(String token, Payload payload);

	public void sendNotification(PushNotification notification);
	
	public boolean isAvailable();
	
	public boolean setUnavailable();
	
	public boolean isDeprecated();
	
	public void setDeprecated(boolean deprecated);
	
	public void close() throws IOException;
	
    public void closeSocket();
    
    public void handleSendError();
}
