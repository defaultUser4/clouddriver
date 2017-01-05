/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.appengine.deploy.ops

import com.google.api.services.appengine.v1.Appengine
import com.google.api.services.appengine.v1.model.Service
import com.google.api.services.appengine.v1.model.TrafficSplit
import com.netflix.spinnaker.clouddriver.appengine.deploy.AppEngineSafeRetry
import com.netflix.spinnaker.clouddriver.appengine.deploy.description.EnableDisableAppEngineDescription
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineLoadBalancer
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineServerGroup
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineTrafficSplit
import com.netflix.spinnaker.clouddriver.appengine.model.ShardBy
import com.netflix.spinnaker.clouddriver.appengine.provider.view.AppEngineClusterProvider
import com.netflix.spinnaker.clouddriver.appengine.provider.view.AppEngineLoadBalancerProvider
import com.netflix.spinnaker.clouddriver.appengine.security.AppEngineCredentials
import com.netflix.spinnaker.clouddriver.appengine.security.AppEngineNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

class EnableAppEngineAtomicOperationSpec extends Specification {
  private static final ACCOUNT_NAME = 'my-appengine-account'
  private static final SERVER_GROUP_NAME = 'app-stack-detail-v000'
  private static final REGION = 'us-central'
  private static final LOAD_BALANCER_NAME = 'default'
  private static final PROJECT = 'my-gcp-project'

  @Shared
  AppEngineSafeRetry safeRetry

  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))
    safeRetry = new AppEngineSafeRetry(maxRetries: 10, maxWaitInterval: 60000, retryIntervalBase: 0, jitterMultiplier: 0)
  }

  void "enable operation should set a server group's allocation to 1"() {
    setup:
      def clusterProviderMock = Mock(AppEngineClusterProvider)
      def loadBalancerProviderMock = Mock(AppEngineLoadBalancerProvider)

      def appEngineMock = Mock(Appengine)
      def appsMock = Mock(Appengine.Apps)
      def servicesMock = Mock(Appengine.Apps.Services)
      def patchMock = Mock(Appengine.Apps.Services.Patch)

      def credentials = new AppEngineNamedAccountCredentials.Builder()
        .credentials(Mock(AppEngineCredentials))
        .name(ACCOUNT_NAME)
        .region(REGION)
        .project(PROJECT)
        .appengine(appEngineMock)
        .build()

      def description = new EnableDisableAppEngineDescription(
        accountName: ACCOUNT_NAME,
        serverGroupName: SERVER_GROUP_NAME,
        credentials: credentials,
        migrateTraffic: false
      )

      def serverGroup = new AppEngineServerGroup(name: SERVER_GROUP_NAME, loadBalancers: [LOAD_BALANCER_NAME])
      def loadBalancer = new AppEngineLoadBalancer(
        name: LOAD_BALANCER_NAME,
        split: new AppEngineTrafficSplit(
          allocations: ["soon-to-have-no-allocation-1": 0.5, "soon-to-have-no-allocation-2": 0.5],
          shardBy: ShardBy.COOKIE
        )
      )

      def expectedService = new Service(
        split: new TrafficSplit(allocations: [(SERVER_GROUP_NAME): 1.0], shardBy: ShardBy.COOKIE)
      )

      @Subject def operation = new EnableAppEngineAtomicOperation(description)
      operation.appEngineClusterProvider = clusterProviderMock
      operation.appEngineLoadBalancerProvider = loadBalancerProviderMock
      operation.safeRetry = safeRetry

    when:
      operation.operate([])

    then:
      1 * clusterProviderMock.getServerGroup(ACCOUNT_NAME, REGION, SERVER_GROUP_NAME) >> serverGroup
      1 * loadBalancerProviderMock.getLoadBalancer(ACCOUNT_NAME, LOAD_BALANCER_NAME) >> loadBalancer

      1 * appEngineMock.apps() >> appsMock
      1 * appsMock.services() >> servicesMock
      1 * servicesMock.patch(PROJECT, LOAD_BALANCER_NAME, expectedService) >> patchMock
      1 * patchMock.setUpdateMask("split") >> patchMock
      1 * patchMock.setMigrateTraffic(false) >> patchMock
      1 * patchMock.execute()
  }
}