/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.inject.servlet;

import static com.google.inject.servlet.ManagedServletPipeline.REQUEST_DISPATCHER_REQUEST;

import com.google.common.collect.Iterators;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Scopes;
import com.google.inject.spi.BindingTargetVisitor;
import com.google.inject.spi.ProviderInstanceBinding;
import com.google.inject.spi.ProviderWithExtensionVisitor;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

/**
 * An internal representation of a filter definition against a particular URI pattern.
 *
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 */
class FilterDefinition implements ProviderWithExtensionVisitor<FilterDefinition> {
  private final Key<? extends Filter> filterKey;
  private final UriPatternMatcher patternMatcher;
  private final Map<String, String> initParams;
  // set only if this was bound to an instance of a Filter.
  private final Filter filterInstance;

  // always set after init is called.
  private final AtomicReference<Filter> filter = new AtomicReference<>();

  public FilterDefinition(
      Key<? extends Filter> filterKey,
      UriPatternMatcher patternMatcher,
      Map<String, String> initParams,
      Filter filterInstance) {
    this.filterKey = filterKey;
    this.patternMatcher = patternMatcher;
    this.initParams = Collections.unmodifiableMap(new HashMap<String, String>(initParams));
    this.filterInstance = filterInstance;
  }

  @Override
  public FilterDefinition get() {
    return this;
  }

  @Override
  public <B, V> V acceptExtensionVisitor(
      BindingTargetVisitor<B, V> visitor, ProviderInstanceBinding<? extends B> binding) {
    if (visitor instanceof ServletModuleTargetVisitor) {
      if (filterInstance != null) {
        return ((ServletModuleTargetVisitor<B, V>) visitor)
            .visit(new InstanceFilterBindingImpl(initParams, filterInstance, patternMatcher));
      } else {
        return ((ServletModuleTargetVisitor<B, V>) visitor)
            .visit(new LinkedFilterBindingImpl(initParams, filterKey, patternMatcher));
      }
    } else {
      return visitor.visit(binding);
    }
  }

  private boolean shouldFilter(String uri) {
    return uri != null && patternMatcher.matches(uri);
  }

  public void init(
      final ServletContext servletContext, Injector injector, Set<Filter> initializedSoFar)
      throws ServletException {

    // This absolutely must be a singleton, and so is only initialized once.
    if (!Scopes.isSingleton(injector.getBinding(filterKey))) {
      throw new ServletException(
          "Filters must be bound as singletons. "
              + filterKey
              + " was not bound in singleton scope.");
    }

    Filter filter = injector.getInstance(filterKey);
    this.filter.set(filter);

    // Only fire init() if this Singleton filter has not already appeared earlier
    // in the filter chain.
    if (initializedSoFar.contains(filter)) {
      return;
    }

    //initialize our filter with the configured context params and servlet context
    filter.init(
        new FilterConfig() {
          @Override
          public String getFilterName() {
            return filterKey.toString();
          }

          @Override
          public ServletContext getServletContext() {
            return servletContext;
          }

          @Override
          public String getInitParameter(String s) {
            return initParams.get(s);
          }

          @Override
          public Enumeration getInitParameterNames() {
            return Iterators.asEnumeration(initParams.keySet().iterator());
          }
        });

    initializedSoFar.add(filter);
  }

  public void destroy(Set<Filter> destroyedSoFar) {
    // filters are always singletons
    Filter reference = filter.get();

    // Do nothing if this Filter was invalid (usually due to not being scoped
    // properly), or was already destroyed. According to Servlet Spec: it is
    // "out of service", and does not need to be destroyed.
    // Also prevent duplicate destroys to the same singleton that may appear
    // more than once on the filter chain.
    if (null == reference || destroyedSoFar.contains(reference)) {
      return;
    }

    try {
      reference.destroy();
    } finally {
      destroyedSoFar.add(reference);
    }
  }

  public Filter getFilterIfMatching(HttpServletRequest request) {

    final String path = ServletUtils.getContextRelativePath(request);
    if (shouldFilter(path)) {
      return filter.get();
    } else {
      return null;
    }
  }

  HttpServletRequest wrapRequest(final HttpServletRequest servletRequest) {
    HttpServletRequest request = new HttpServletRequestWrapper(servletRequest) {
      private boolean pathComputed;
      private String path;

      private boolean pathInfoComputed;
      private String pathInfo;

      @Override
      public String getPathInfo() {
        if (!isPathInfoComputed()) {
          String servletPath = getServletPath();
          int servletPathLength = servletPath.length();
          String requestUri = getRequestURI();
          pathInfo = requestUri.substring(getContextPath().length()).replaceAll("[/]{2,}", "/");
          // See: http://code.google.com/p/google-guice/issues/detail?id=372
          if (pathInfo.startsWith(servletPath)) {
            pathInfo = pathInfo.substring(servletPathLength);
            // Corner case: when servlet path & request path match exactly (without trailing '/'),
            // then pathinfo is null.
            if (pathInfo.isEmpty() && servletPathLength > 0) {
              pathInfo = null;
            }
          } else {
            pathInfo = null; // we know nothing additional about the URI.
          }
          pathInfoComputed = true;
        }

        return pathInfo;
      }

      // NOTE(dhanji): These two are a bit of a hack to help ensure that request dispatcher-sent
      // requests don't use the same path info that was memoized for the original request.
      // NOTE(iqshum): I don't think this is possible, since the dispatcher-sent request would
      // perform its own wrapping.
      private boolean isPathInfoComputed() {
        return pathInfoComputed
          && !(null != servletRequest.getAttribute(REQUEST_DISPATCHER_REQUEST));
      }

      private boolean isPathComputed() {
        return pathComputed
          && !(null != servletRequest.getAttribute(REQUEST_DISPATCHER_REQUEST));
      }

      @Override
      public String getServletPath() {
        return computePath();
      }

      @Override
      public String getPathTranslated() {
        final String info = getPathInfo();

        return (null == info) ? null : getRealPath(info);
      }

      // Memoizer pattern.
      private String computePath() {
        if (!isPathComputed()) {
          String servletPath = super.getServletPath();
          path = patternMatcher.extractPath(servletPath);
          pathComputed = true;

          if (null == path) {
            path = servletPath;
          }
        }

        return path;
      }
    };
    return request;
  }

  //VisibleForTesting
  Filter getFilter() {
    return filter.get();
  }
}
