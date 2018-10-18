/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder.steps;

import com.google.cloud.tools.jib.async.AsyncStep;
import com.google.cloud.tools.jib.async.NonBlockingSteps;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.builder.TimerEventDispatcher;
import com.google.cloud.tools.jib.configuration.BuildConfiguration;
import com.google.cloud.tools.jib.event.events.ImageCreatedEvent;
import com.google.cloud.tools.jib.event.events.LogEvent;
import com.google.cloud.tools.jib.image.DescriptorDigest;
import com.google.cloud.tools.jib.image.Image;
import com.google.cloud.tools.jib.image.Layer;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ImageToJsonTranslator;
import com.google.cloud.tools.jib.json.JsonTemplateMapper;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/** Pushes the final image. Outputs the pushed image digest. */
class PushImageStep implements AsyncStep<DescriptorDigest>, Callable<DescriptorDigest> {

  private static final String DESCRIPTION = "Pushing new image";

  private final BuildConfiguration buildConfiguration;
  private final AuthenticatePushStep authenticatePushStep;

  private final PushLayersStep pushBaseImageLayersStep;
  private final PushLayersStep pushApplicationLayersStep;
  private final PushContainerConfigurationStep pushContainerConfigurationStep;
  private final BuildImageStep buildImageStep;

  private final ListeningExecutorService listeningExecutorService;
  private final ListenableFuture<DescriptorDigest> listenableFuture;

  PushImageStep(
      ListeningExecutorService listeningExecutorService,
      BuildConfiguration buildConfiguration,
      AuthenticatePushStep authenticatePushStep,
      PushLayersStep pushBaseImageLayersStep,
      PushLayersStep pushApplicationLayersStep,
      PushContainerConfigurationStep pushContainerConfigurationStep,
      BuildImageStep buildImageStep) {
    this.listeningExecutorService = listeningExecutorService;
    this.buildConfiguration = buildConfiguration;
    this.authenticatePushStep = authenticatePushStep;

    this.pushBaseImageLayersStep = pushBaseImageLayersStep;
    this.pushApplicationLayersStep = pushApplicationLayersStep;
    this.pushContainerConfigurationStep = pushContainerConfigurationStep;
    this.buildImageStep = buildImageStep;

    listenableFuture =
        Futures.whenAllSucceed(
                pushBaseImageLayersStep.getFuture(),
                pushApplicationLayersStep.getFuture(),
                pushContainerConfigurationStep.getFuture())
            .call(this, listeningExecutorService);
  }

  @Override
  public ListenableFuture<DescriptorDigest> getFuture() {
    return listenableFuture;
  }

  @Override
  public DescriptorDigest call() throws ExecutionException, InterruptedException {
    ImmutableList.Builder<ListenableFuture<?>> dependenciesBuilder = ImmutableList.builder();
    dependenciesBuilder.add(authenticatePushStep.getFuture());
    for (AsyncStep<PushBlobStep> pushBlobStepStep : NonBlockingSteps.get(pushBaseImageLayersStep)) {
      dependenciesBuilder.add(pushBlobStepStep.getFuture());
    }
    for (AsyncStep<PushBlobStep> pushBlobStepStep :
        NonBlockingSteps.get(pushApplicationLayersStep)) {
      dependenciesBuilder.add(pushBlobStepStep.getFuture());
    }
    dependenciesBuilder.add(NonBlockingSteps.get(pushContainerConfigurationStep).getFuture());
    dependenciesBuilder.add(NonBlockingSteps.get(buildImageStep).getFuture());
    return Futures.whenAllSucceed(dependenciesBuilder.build())
        .call(this::afterPushSteps, listeningExecutorService)
        .get()
        .get()
        .get();
  }

  private ListenableFuture<ListenableFuture<DescriptorDigest>> afterPushSteps()
      throws ExecutionException {
    List<ListenableFuture<?>> dependencies = new ArrayList<>();
    for (AsyncStep<PushBlobStep> pushBlobStepStep : NonBlockingSteps.get(pushBaseImageLayersStep)) {
      dependencies.add(NonBlockingSteps.get(pushBlobStepStep).getFuture());
    }
    for (AsyncStep<PushBlobStep> pushBlobStepStep :
        NonBlockingSteps.get(pushApplicationLayersStep)) {
      dependencies.add(NonBlockingSteps.get(pushBlobStepStep).getFuture());
    }
    dependencies.add(
        NonBlockingSteps.get(NonBlockingSteps.get(pushContainerConfigurationStep)).getFuture());
    return Futures.whenAllSucceed(dependencies)
        .call(this::afterAllPushed, listeningExecutorService);
  }

  private ListenableFuture<DescriptorDigest> afterAllPushed()
      throws ExecutionException, IOException {
    try (TimerEventDispatcher ignored =
        new TimerEventDispatcher(buildConfiguration.getEventDispatcher(), DESCRIPTION)) {
      RegistryClient registryClient =
          buildConfiguration
              .newTargetImageRegistryClientFactory()
              .setAuthorization(NonBlockingSteps.get(authenticatePushStep))
              .newRegistryClient();

      // Constructs the image.
      Image<Layer> image = NonBlockingSteps.get(NonBlockingSteps.get(buildImageStep));
      ImageToJsonTranslator imageToJsonTranslator = new ImageToJsonTranslator(image);

      // Gets the image manifest to push.
      BlobDescriptor containerConfigurationBlobDescriptor =
          NonBlockingSteps.get(
              NonBlockingSteps.get(NonBlockingSteps.get(pushContainerConfigurationStep)));
      BuildableManifestTemplate manifestTemplate =
          imageToJsonTranslator.getManifestTemplate(
              buildConfiguration.getTargetFormat(), containerConfigurationBlobDescriptor);

      // Pushes to all target image tags.
      List<ListenableFuture<Void>> pushAllTagsFutures = new ArrayList<>();
      for (String tag : buildConfiguration.getAllTargetImageTags()) {
        pushAllTagsFutures.add(
            listeningExecutorService.submit(
                () -> {
                  buildConfiguration
                      .getEventDispatcher()
                      .dispatch(LogEvent.info("Tagging with " + tag + "..."));
                  registryClient.pushManifest(manifestTemplate, tag);
                  return null;
                }));
      }

      DescriptorDigest imageDigest =
          JsonTemplateMapper.toBlob(manifestTemplate)
              .writeTo(ByteStreams.nullOutputStream())
              .getDigest();

      Callable<DescriptorDigest> afterPushTags =
          () -> {
            ImageCreatedEvent event =
                new ImageCreatedEvent(
                    image, imageDigest, containerConfigurationBlobDescriptor.getDigest());
            buildConfiguration.getEventDispatcher().dispatch(event);

            return imageDigest;
          };
      return Futures.whenAllSucceed(pushAllTagsFutures)
          .call(afterPushTags, listeningExecutorService);
    }
  }
}
