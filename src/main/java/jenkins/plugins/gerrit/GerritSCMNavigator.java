// Copyright (C) 2022 Réda Housni Alaoui <reda-alaoui@hey.com>
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package jenkins.plugins.gerrit;

import static java.util.Optional.ofNullable;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.common.ProjectInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.scm.api.SCMSourceObserver;
import jenkins.scm.api.trait.SCMNavigatorRequest;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import jenkins.scm.api.trait.SCMTrait;
import jenkins.scm.impl.form.NamedArrayList;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class GerritSCMNavigator extends SCMNavigator {

  @CheckForNull private String serverUrl;
  private boolean insecureHttps;
  @CheckForNull private String credentialsId;
  @Nonnull private List<SCMTrait<? extends SCMTrait<?>>> traits;

  public GerritSCMNavigator() {
    this(null, false, null, Collections.emptyList());
  }

  @DataBoundConstructor
  public GerritSCMNavigator(
      String serverUrl,
      boolean insecureHttps,
      String credentialsId,
      List<SCMTrait<? extends SCMTrait<?>>> traits) {
    this.serverUrl = StringUtils.trimToNull(serverUrl);
    this.insecureHttps = insecureHttps;
    this.credentialsId = StringUtils.defaultIfBlank(credentialsId, null);
    this.traits =
        ofNullable(traits).map(Collections::unmodifiableList).orElseGet(Collections::emptyList);
  }

  @Nonnull
  @Override
  protected String id() {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("server-url", serverUrl);
    attributes.put("credentials-id", credentialsId);

    return attributes
        .entrySet()
        .stream()
        .map(attribute -> attribute.getKey() + "=" + attribute.getValue())
        .collect(Collectors.joining("::"));
  }

  @Override
  public void visitSources(@Nonnull SCMSourceObserver observer)
      throws IOException, InterruptedException {
    GerritURI gerritURI;
    GerritApi gerritApi;
    try {
      gerritURI = getGerritURI();
      gerritApi = createGerritApiBuilder(observer).build();
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }

    try (GerritSCMNavigatorRequest request =
        new GerritSCMNavigatorContext().withTraits(traits).newRequest(this, observer)) {

      SCMNavigatorRequest.SourceLambda sourceLambda =
          projectName ->
              new GerritSCMSourceBuilder(
                      getId() + "::" + projectName,
                      projectName,
                      gerritURI,
                      insecureHttps,
                      credentialsId)
                  .withRequest(request)
                  .build();

      PagedCodeProjectsRequest projectsRequest = new PagedCodeProjectsRequest(gerritApi);
      for (ProjectInfo projectInfo : projectsRequest) {
        boolean stop = request.process(projectInfo.name, sourceLambda, null);
        if (stop) {
          break;
        }
        checkInterrupt();
      }
    }
  }

  private GerritApiBuilder createGerritApiBuilder(@Nonnull SCMSourceObserver observer)
      throws URISyntaxException {
    return new GerritApiBuilder()
        .logger(observer.getListener().getLogger())
        .gerritApiUrl(getGerritApiURI())
        .insecureHttps(insecureHttps)
        .credentials(lookupCredentials(observer.getContext()));
  }

  @CheckForNull
  private StandardUsernamePasswordCredentials lookupCredentials(Item context) {
    if (credentialsId == null) {
      return null;
    }
    return CredentialsMatchers.firstOrNull(
        CredentialsProvider.lookupCredentials(
            StandardUsernamePasswordCredentials.class,
            context,
            ACL.SYSTEM,
            URIRequirementBuilder.fromUri(serverUrl).build()),
        CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId)));
  }

  private URIish getGerritApiURI() throws URISyntaxException {
    return getGerritURI().getApiURI();
  }

  private GerritURI getGerritURI() throws URISyntaxException {
    return new GerritURI(new URIish(serverUrl));
  }

  @Extension
  public static final class DescriptorImpl extends SCMNavigatorDescriptor {

    private final GerritSCMSource.DescriptorImpl delegate = new GerritSCMSource.DescriptorImpl();

    @Override
    public String getDisplayName() {
      return "Gerrit Server";
    }

    @Override
    public String getIconClassName() {
      return GerritLogo.ICON_CLASS_NAME;
    }

    @Override
    public SCMNavigator newInstance(String name) {
      return new GerritSCMNavigator(
          null, false, null, new ArrayList<>(delegate.getTraitsDefaults()));
    }

    @NonNull
    @Override
    public String getDescription() {
      return super.getDescription();
    }

    public FormValidation doCheckServerUrl(@QueryParameter String value) {
      try {
        new GerritURI(new URIish(value)).getApiURI();
      } catch (URISyntaxException e) {
        return FormValidation.error(e.getMessage());
      }
      return FormValidation.ok();
    }

    public ListBoxModel doFillCredentialsIdItems(
        @AncestorInPath Item context,
        @QueryParameter String serverUrl,
        @QueryParameter String credentialsId) {
      if (context == null && !Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)
          || context != null && !context.hasPermission(Item.EXTENDED_READ)) {
        return new StandardListBoxModel().includeCurrentValue(credentialsId);
      }
      return new StandardListBoxModel()
          .includeEmptyValue()
          .includeMatchingAs(
              context instanceof Queue.Task
                  ? Tasks.getAuthenticationOf((Queue.Task) context)
                  : ACL.SYSTEM,
              context,
              StandardUsernameCredentials.class,
              URIRequirementBuilder.fromUri(serverUrl).build(),
              GitClient.CREDENTIALS_MATCHER)
          .includeCurrentValue(credentialsId);
    }

    public List<NamedArrayList<? extends SCMSourceTraitDescriptor>> getTraitsDescriptorLists() {
      return delegate.getTraitsDescriptorLists();
    }

    @Override
    public List<SCMTrait<? extends SCMTrait<?>>> getTraitsDefaults() {
      return new ArrayList<>(delegate.getTraitsDefaults());
    }
  }

  @CheckForNull
  public String getServerUrl() {
    return serverUrl;
  }

  @DataBoundSetter
  public void setServerUrl(String serverUrl) {
    this.serverUrl = serverUrl;
  }

  public boolean isInsecureHttps() {
    return insecureHttps;
  }

  @DataBoundSetter
  public void setInsecureHttps(boolean insecureHttps) {
    this.insecureHttps = insecureHttps;
  }

  @CheckForNull
  public String getCredentialsId() {
    return credentialsId;
  }

  @DataBoundSetter
  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  @Nonnull
  public List<SCMTrait<? extends SCMTrait<?>>> getTraits() {
    return traits;
  }

  @DataBoundSetter
  public void setTraits(List<SCMTrait<? extends SCMTrait<?>>> traits) {
    this.traits =
        ofNullable(traits).map(Collections::unmodifiableList).orElseGet(Collections::emptyList);
  }
}
