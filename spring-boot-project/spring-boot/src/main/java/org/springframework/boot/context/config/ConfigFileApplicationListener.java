/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link EnvironmentPostProcessor} that configures the context environment by loading
 * properties from well known file locations. By default properties will be loaded from
 * 'application.properties' and/or 'application.yml' files in the following locations:
 * <ul>
 * <li>file:./config/</li>
 * <li>file:./config/{@literal *}/</li>
 * <li>file:./</li>
 * <li>classpath:config/</li>
 * <li>classpath:</li>
 * </ul>
 * The list is ordered by precedence (properties defined in locations higher in the list
 * override those defined in lower locations).
 * <p>
 * Alternative search locations and names can be specified using
 * {@link #setSearchLocations(String)} and {@link #setSearchNames(String)}.
 * <p>
 * Additional files will also be loaded based on active profiles. For example if a 'web'
 * profile is active 'application-web.properties' and 'application-web.yml' will be
 * considered.
 * <p>
 * The 'spring.config.name' property can be used to specify an alternative name to load
 * and the 'spring.config.location' property can be used to specify alternative search
 * locations or specific files.
 * <p>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 * @author Madhura Bhave
 * @author Scott Frederick
 * @since 1.0.0
 */
public class ConfigFileApplicationListener implements EnvironmentPostProcessor, SmartApplicationListener, Ordered {

	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	// Note the order is from least to most specific (last one wins)
	private static final String DEFAULT_SEARCH_LOCATIONS = "classpath:/,classpath:/config/,file:./,file:./config/*/,file:./config/";

	private static final String DEFAULT_NAMES = "application";

	private static final Set<String> NO_SEARCH_NAMES = Collections.singleton(null);

	private static final Bindable<String[]> STRING_ARRAY = Bindable.of(String[].class);

	private static final Bindable<List<String>> STRING_LIST = Bindable.listOf(String.class);

	private static final Set<String> LOAD_FILTERED_PROPERTY;

	static {
		Set<String> filteredProperties = new HashSet<>();
		filteredProperties.add("spring.profiles.active");
		filteredProperties.add("spring.profiles.include");
		LOAD_FILTERED_PROPERTY = Collections.unmodifiableSet(filteredProperties);
	}

	/**
	 * The "active profiles" property name.
	 */
	public static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

	/**
	 * The "includes profiles" property name.
	 */
	public static final String INCLUDE_PROFILES_PROPERTY = "spring.profiles.include";

	/**
	 * The "config name" property name.
	 */
	public static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	/**
	 * The "config location" property name.
	 */
	public static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

	/**
	 * The "config additional location" property name.
	 */
	public static final String CONFIG_ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLog logger = new DeferredLog();

	private static final Resource[] EMPTY_RESOURCES = {};

	private static final Comparator<File> FILE_COMPARATOR = Comparator.comparing(File::getAbsolutePath);

	private String searchLocations;

	private String names;

	private int order = DEFAULT_ORDER;

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
				|| ApplicationPreparedEvent.class.isAssignableFrom(eventType);
	}

	/**
	 * 加载默认配置 application.properties/yml 等配置文件
	 */
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		// 触发【环境准备完毕】事件 (解析配置文件)
		if (event instanceof ApplicationEnvironmentPreparedEvent) {
			onApplicationEnvironmentPreparedEvent((ApplicationEnvironmentPreparedEvent) event);
		}
		// 触发【容器准备完毕】事件 (重置 defaultProperties 优先级最低)
		if (event instanceof ApplicationPreparedEvent) {
			onApplicationPreparedEvent(event);
		}
	}

	private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
		// 获取环境前置处理器
		List<EnvironmentPostProcessor> postProcessors = loadPostProcessors();
		postProcessors.add(this);
		AnnotationAwareOrderComparator.sort(postProcessors);
		for (EnvironmentPostProcessor postProcessor : postProcessors) {
			// 环境处理器分别处理
			postProcessor.postProcessEnvironment(event.getEnvironment(), event.getSpringApplication());
		}
	}

	List<EnvironmentPostProcessor> loadPostProcessors() {
		return SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class, getClass().getClassLoader());
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		// 环境配置前置处理器进行配置文件添加
		addPropertySources(environment, application.getResourceLoader());
	}

	private void onApplicationPreparedEvent(ApplicationEvent event) {
		this.logger.switchTo(ConfigFileApplicationListener.class);
		// 添加 BeanFactory 前置执行器
		addPostProcessors(((ApplicationPreparedEvent) event).getApplicationContext());
	}

	/**
	 * Add config file property sources to the specified environment.
	 * @param environment the environment to add source to
	 * @param resourceLoader the resource loader
	 * @see #addPostProcessors(ConfigurableApplicationContext)
	 */
	protected void addPropertySources(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
		RandomValuePropertySource.addToEnvironment(environment);
		// 配置加载器进行加载
		new Loader(environment, resourceLoader).load();
	}

	/**
	 * Add appropriate post-processors to post-configure the property-sources.
	 * @param context the context to configure
	 */
	protected void addPostProcessors(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(new PropertySourceOrderingPostProcessor(context));
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the search locations that will be considered as a comma-separated list. Each
	 * search location should be a directory path (ending in "/") and it will be prefixed
	 * by the file names constructed from {@link #setSearchNames(String) search names} and
	 * profiles (if any) plus file extensions supported by the properties loaders.
	 * Locations are considered in the order specified, with later items taking precedence
	 * (like a map merge).
	 * @param locations the search locations
	 */
	public void setSearchLocations(String locations) {
		Assert.hasLength(locations, "Locations must not be empty");
		this.searchLocations = locations;
	}

	/**
	 * Sets the names of the files that should be loaded (excluding file extension) as a
	 * comma-separated list.
	 * @param names the names to load
	 */
	public void setSearchNames(String names) {
		Assert.hasLength(names, "Names must not be empty");
		this.names = names;
	}

	/**
	 * 重置 defaultProperties 优先级到最低
	 * {@link BeanFactoryPostProcessor} to re-order our property sources below any
	 * {@code @PropertySource} items added by the {@link ConfigurationClassPostProcessor}.
	 */
	private static class PropertySourceOrderingPostProcessor implements BeanFactoryPostProcessor, Ordered {

		private ConfigurableApplicationContext context;

		PropertySourceOrderingPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
			reorderSources(this.context.getEnvironment());
		}

		private void reorderSources(ConfigurableEnvironment environment) {
			PropertySource<?> defaultProperties = environment.getPropertySources().remove(DEFAULT_PROPERTIES);
			if (defaultProperties != null) {
				environment.getPropertySources().addLast(defaultProperties);
			}
		}

	}

	/**
	 * Loads candidate property sources and configures the active profiles.
	 */
	private class Loader {

		private final Log logger = ConfigFileApplicationListener.this.logger;

		private final ConfigurableEnvironment environment;

		private final PropertySourcesPlaceholdersResolver placeholdersResolver;

		private final ResourceLoader resourceLoader;

		private final List<PropertySourceLoader> propertySourceLoaders;

		private Deque<Profile> profiles;

		private List<Profile> processedProfiles;

		private boolean activatedProfiles;

		private Map<Profile, MutablePropertySources> loaded;

		private Map<DocumentsCacheKey, List<Document>> loadDocumentsCache = new HashMap<>();

		Loader(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
			this.environment = environment;
			this.placeholdersResolver = new PropertySourcesPlaceholdersResolver(this.environment);
			this.resourceLoader = (resourceLoader != null) ? resourceLoader : new DefaultResourceLoader(null);
			// 文件 application 配置文件的资源加载器, 包括 properties/xml/yml/yaml 扩展名
			// 从 spring.factories 中加载 PropertySourceLoader 接⼝的具体实现类, 默认有两个实现类
			// PropertiesPropertySourceLoader, 用于加载 properties/xml 格式的配置文件
			// YamlPropertySourceLoader, 用于加载 yml/yaml 格式的配置文件
			// nacos会在这里再置入两个实现类, NacosXmlPropertySourceLoader/NacosJsonPropertySourceLoader 且 优先级高于默认的实现类
			this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(PropertySourceLoader.class,
					getClass().getClassLoader());
		}

		// 加载配置文件
		void load() {
			FilteredPropertySource.apply(this.environment, DEFAULT_PROPERTIES, LOAD_FILTERED_PROPERTY,
					(defaultProperties) -> {
						this.profiles = new LinkedList<>();
						this.processedProfiles = new LinkedList<>();
						this.activatedProfiles = false;
						this.loaded = new LinkedHashMap<>();
						// 初始化 profiles
						initializeProfiles();
						while (!this.profiles.isEmpty()) {
							Profile profile = this.profiles.poll();
							// 是否是默认 profile
							if (isDefaultProfile(profile)) {
								addProfileToEnvironment(profile.getName());
							}
							// 加载 profile (首先拿到的是 profile=null, 会直接进入该方法加载配置文件)
							// addToLoaded(MutablePropertySources::addLast, false) 获取一个 consumer, 用来回调处理配置文件Document对象
							// 	将配置文件(propertySource)优先级置入最后
							load(profile, this::getPositiveProfileFilter,
									addToLoaded(MutablePropertySources::addLast, false));
							this.processedProfiles.add(profile);
						}
						// 再次加载默认配置文件 (getNegativeProfileFilter() 和 getPositiveProfileFilter() 的区别)
						load(null, this::getNegativeProfileFilter, addToLoaded(MutablePropertySources::addFirst, true));
						// 添加所有的 properties 到 environment
						addLoadedPropertySources();
						// 应用激活的 profile
						applyActiveProfiles(defaultProperties);
					});
		}

		/**
		 * Initialize profile information from both the {@link Environment} active
		 * profiles and any {@code spring.profiles.active}/{@code spring.profiles.include}
		 * properties that are already set.
		 */
		private void initializeProfiles() {
			// The default profile for these purposes is represented as null. We add it
			// first so that it is processed first and has lowest priority.
			// 第一个 profile 为 null, 这样能保证首个加载 application.properties/yml
			this.profiles.add(null);
			Binder binder = Binder.get(this.environment);
			// 通过启动参数获取激活的、包含的、以及其他 profile
			Set<Profile> activatedViaProperty = getProfiles(binder, ACTIVE_PROFILES_PROPERTY);
			Set<Profile> includedViaProperty = getProfiles(binder, INCLUDE_PROFILES_PROPERTY);
			List<Profile> otherActiveProfiles = getOtherActiveProfiles(activatedViaProperty, includedViaProperty);
			this.profiles.addAll(otherActiveProfiles);
			// Any pre-existing active profiles set via property sources (e.g.
			// System properties) take precedence over those added in config files.
			this.profiles.addAll(includedViaProperty);
			addActiveProfiles(activatedViaProperty);
			// 无额外配置 profile 时, 使用默认
			// 这里的没有配置并不是指 applicatio n配置文件中没有配置, 而是命令行、获取 main 方法传入等其他方法配置
			// 我们并未配置任何 active 的 profile, 这里或产生这样的数据 profiles=[null, "default"]
			if (this.profiles.size() == 1) { // only has null profile
				for (String defaultProfileName : this.environment.getDefaultProfiles()) {
					Profile defaultProfile = new Profile(defaultProfileName, true);
					this.profiles.add(defaultProfile);
				}
			}
		}

		private List<Profile> getOtherActiveProfiles(Set<Profile> activatedViaProperty,
													 Set<Profile> includedViaProperty) {
			return Arrays.stream(this.environment.getActiveProfiles()).map(Profile::new).filter(
					(profile) -> !activatedViaProperty.contains(profile) && !includedViaProperty.contains(profile))
					.collect(Collectors.toList());
		}

		void addActiveProfiles(Set<Profile> profiles) {
			if (profiles.isEmpty()) {
				return;
			}
			if (this.activatedProfiles) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Profiles already activated, '" + profiles + "' will not be applied");
				}
				return;
			}
			this.profiles.addAll(profiles);
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Activated activeProfiles " + StringUtils.collectionToCommaDelimitedString(profiles));
			}
			this.activatedProfiles = true;
			// 移除 default
			removeUnprocessedDefaultProfiles();
		}

		private void removeUnprocessedDefaultProfiles() {
			this.profiles.removeIf((profile) -> (profile != null && profile.isDefaultProfile()));
		}

		private DocumentFilter getPositiveProfileFilter(Profile profile) {
			return (Document document) -> {
				// 当前环境变量是空, 返回true, 表示需要配置环境环境变量 profile=[null, default]
				// 先读取 application 等默认配置文件, 查看是否有环境变量 profile 需要配置
				if (profile == null) {
					return ObjectUtils.isEmpty(document.getProfiles());
				}
				// 环境变量profile不为空(可能为系统默认 default, 或自己在配置文件application等配置好的 dev)
				// 判断配置文件中的环境变量是否已经包含了当前环境变量 且 是否包含已经激活的环境变量
				return ObjectUtils.containsElement(document.getProfiles(), profile.getName())
						&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles()));
			};
		}

		private DocumentFilter getNegativeProfileFilter(Profile profile) {
			return (Document document) -> (
					// 当前环境变量为空 并且 配置文件中环境变量不为空 并且 包含已经激活的环境变量
					profile == null && !ObjectUtils.isEmpty(document.getProfiles())
							&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles()))
			);
		}

		private DocumentConsumer addToLoaded(BiConsumer<MutablePropertySources, PropertySource<?>> addMethod,
											 boolean checkForExisting) {
			return (profile, document) -> {
				// 是否校验存在
				if (checkForExisting) {
					for (MutablePropertySources merged : this.loaded.values()) {
						if (merged.contains(document.getPropertySource().getName())) {
							return;
						}
					}
				}
				MutablePropertySources merged = this.loaded.computeIfAbsent(profile,
						(k) -> new MutablePropertySources());
				// 将配置文件优先级置入最后
				addMethod.accept(merged, document.getPropertySource());
			};
		}

		private void load(Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// 获取并遍历所有待搜索的位置
			getSearchLocations().forEach((location) -> {
				boolean isDirectory = location.endsWith("/");
				// 如果是目录, 获取所有待加载的配置文件名(默认为application); 如果不是目录, 放入 null
				Set<String> names = isDirectory ? getSearchNames() : NO_SEARCH_NAMES;
				// 加载每个位置的配置文件
				names.forEach((name) -> load(location, name, profile, filterFactory, consumer));
			});
		}

		private void load(String location, String name, Profile profile, DocumentFilterFactory filterFactory,
						  DocumentConsumer consumer) {
			// 无配置文件名称、location 直接对应文件
			if (!StringUtils.hasText(name)) {
				for (PropertySourceLoader loader : this.propertySourceLoaders) {
					if (canLoadFileExtension(loader, location)) {
						// 通过 location 加载文件
						load(loader, location, profile, filterFactory.getDocumentFilter(profile), consumer);
						return;
					}
				}
				throw new IllegalStateException("File extension of config file location '" + location
						+ "' is not known to any PropertySourceLoader. If the location is meant to reference "
						+ "a directory, it must end in '/'");
			}
			// 分别扫描对应 location 目录下的配置文件
			Set<String> processed = new HashSet<>();
			// 获取配置类加载器 (PropertiesPropertySourceLoader、YamlPropertySourceLoader) 分别进行加载
			for (PropertySourceLoader loader : this.propertySourceLoaders) {
				// 分别获取文件扩展名(properties、xml、yml、yaml)
				for (String fileExtension : loader.getFileExtensions()) {
					if (processed.add(fileExtension)) {
						// 加载具体的配置文件
						loadForFileExtension(loader, location + name, "." + fileExtension, profile, filterFactory,
								consumer);
					}
				}
			}
		}

		private boolean canLoadFileExtension(PropertySourceLoader loader, String name) {
			return Arrays.stream(loader.getFileExtensions())
					.anyMatch((fileExtension) -> StringUtils.endsWithIgnoreCase(name, fileExtension));
		}

		private void loadForFileExtension(PropertySourceLoader loader, String prefix, String fileExtension,
										  Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// 获取默认环境文件过滤器
			DocumentFilter defaultFilter = filterFactory.getDocumentFilter(null);
			// 获取指定环境文件过滤器
			DocumentFilter profileFilter = filterFactory.getDocumentFilter(profile);
			// profile 非空
			// profile=[null, "dev", "prod"]
			// profile=[null, "defualt"]
			if (profile != null) {
				// Try profile-specific file & profile section in profile file (gh-340)
				// 尝试加载指定环境配置文件: application-default.properties/bootstrap-default.properties
				String profileSpecificFile = prefix + "-" + profile + fileExtension;
				load(loader, profileSpecificFile, profile, defaultFilter, consumer);
				load(loader, profileSpecificFile, profile, profileFilter, consumer);
				// Try profile specific sections in files we've already processed
				// 尝试加载已经执行了的环境配置
				for (Profile processedProfile : this.processedProfiles) {
					if (processedProfile != null) {
						String previouslyLoaded = prefix + "-" + processedProfile + fileExtension;
						load(loader, previouslyLoaded, profile, profileFilter, consumer);
					}
				}
			}
			// Also try the profile-specific section (if any) of the normal file
			// 加载常规的配置文件  application.properties/bootstrap.properties
			// 由于首次进来的 profile = null, profileFilter = defaultFilter
			load(loader, prefix + fileExtension, profile, profileFilter, consumer);
		}

		private void load(PropertySourceLoader loader, String location, Profile profile, DocumentFilter filter,
						  DocumentConsumer consumer) {
			// 获取资源
			Resource[] resources = getResources(location);
			for (Resource resource : resources) {
				try {
					if (resource == null || !resource.exists()) {
						if (this.logger.isTraceEnabled()) {
							StringBuilder description = getDescription("Skipped missing config ", location, resource,
									profile);
							this.logger.trace(description);
						}
						continue;
					}
					if (!StringUtils.hasText(StringUtils.getFilenameExtension(resource.getFilename()))) {
						if (this.logger.isTraceEnabled()) {
							StringBuilder description = getDescription("Skipped empty config extension ", location,
									resource, profile);
							this.logger.trace(description);
						}
						continue;
					}
					if (resource.isFile() && isPatternLocation(location) && hasHiddenPathElement(resource)) {
						if (this.logger.isTraceEnabled()) {
							StringBuilder description = getDescription("Skipped location with hidden path element ",
									location, resource, profile);
							this.logger.trace(description);
						}
						continue;
					}
					String name = "applicationConfig: [" + getLocationName(location, resource) + "]";
					// 加载为 document 对象(调用加载器的 load 方法进行配置文件的加载)
					List<Document> documents = loadDocuments(loader, name, resource);
					if (CollectionUtils.isEmpty(documents)) {
						if (this.logger.isTraceEnabled()) {
							StringBuilder description = getDescription("Skipped unloaded config ", location, resource,
									profile);
							this.logger.trace(description);
						}
						continue;
					}
					List<Document> loaded = new ArrayList<>();
					for (Document document : documents) {
						// 文档是否匹配过滤器
						if (filter.match(document)) {
							// 默认的配置文件中 application/bootstrap添加配置 spring.profiles.active=dev
							// 处理到默认配置文件时, 我们会通过解析配置文件新增一个 profile 到 profiles 中
							// 	最初的 profiles=[null, "default"]; ⽽后, 我们消费了null, profiles=["default"].
							// 	现在, 我们添加⼀个 profile="test"; 那么, profiles=["default", "test"]
							//	⾏ removeUnprocessedDefaultProfiles, 将会移除default. 所以, 最终profiles=["test"].
							addActiveProfiles(document.getActiveProfiles());
							addIncludedProfiles(document.getIncludeProfiles());
							loaded.add(document);
						}
					}
					Collections.reverse(loaded);
					if (!loaded.isEmpty()) {
						// 处理被加载的 document
						loaded.forEach((document) -> consumer.accept(profile, document));
						if (this.logger.isDebugEnabled()) {
							StringBuilder description = getDescription("Loaded config file ", location, resource,
									profile);
							this.logger.debug(description);
						}
					}
				}
				catch (Exception ex) {
					StringBuilder description = getDescription("Failed to load property source from ", location,
							resource, profile);
					throw new IllegalStateException(description.toString(), ex);
				}
			}
		}

		private boolean hasHiddenPathElement(Resource resource) throws IOException {
			String cleanPath = StringUtils.cleanPath(resource.getFile().getAbsolutePath());
			for (Path value : Paths.get(cleanPath)) {
				if (value.toString().startsWith("..")) {
					return true;
				}
			}
			return false;
		}

		private String getLocationName(String location, Resource resource) {
			if (!location.contains("*")) {
				return location;
			}
			if (resource instanceof FileSystemResource) {
				return ((FileSystemResource) resource).getPath();
			}
			return resource.getDescription();
		}

		private Resource[] getResources(String location) {
			try {
				if (isPatternLocation(location)) {
					return getResourcesFromPatternLocation(location);
				}
				return new Resource[] { this.resourceLoader.getResource(location) };
			}
			catch (Exception ex) {
				return EMPTY_RESOURCES;
			}
		}

		private boolean isPatternLocation(String location) {
			return location.contains("*");
		}

		private Resource[] getResourcesFromPatternLocation(String location) throws IOException {
			String directoryPath = location.substring(0, location.indexOf("*/"));
			Resource resource = this.resourceLoader.getResource(directoryPath);
			File[] files = resource.getFile().listFiles(File::isDirectory);
			if (files != null) {
				String fileName = location.substring(location.lastIndexOf("/") + 1);
				Arrays.sort(files, FILE_COMPARATOR);
				return Arrays.stream(files).map((file) -> file.listFiles((dir, name) -> name.equals(fileName)))
						.filter(Objects::nonNull).flatMap((Function<File[], Stream<File>>) Arrays::stream)
						.map(FileSystemResource::new).toArray(Resource[]::new);
			}
			return EMPTY_RESOURCES;
		}

		private void addIncludedProfiles(Set<Profile> includeProfiles) {
			LinkedList<Profile> existingProfiles = new LinkedList<>(this.profiles);
			this.profiles.clear();
			this.profiles.addAll(includeProfiles);
			this.profiles.removeAll(this.processedProfiles);
			this.profiles.addAll(existingProfiles);
		}

		private List<Document> loadDocuments(PropertySourceLoader loader, String name, Resource resource)
				throws IOException {
			DocumentsCacheKey cacheKey = new DocumentsCacheKey(loader, resource);
			List<Document> documents = this.loadDocumentsCache.get(cacheKey);
			if (documents == null) {
				List<PropertySource<?>> loaded = loader.load(name, resource);
				documents = asDocuments(loaded);
				this.loadDocumentsCache.put(cacheKey, documents);
			}
			return documents;
		}

		private List<Document> asDocuments(List<PropertySource<?>> loaded) {
			if (loaded == null) {
				return Collections.emptyList();
			}
			return loaded.stream().map((propertySource) -> {
				Binder binder = new Binder(ConfigurationPropertySources.from(propertySource),
						this.placeholdersResolver);
				String[] profiles = binder.bind("spring.profiles", STRING_ARRAY).orElse(null);
				Set<Profile> activeProfiles = getProfiles(binder, ACTIVE_PROFILES_PROPERTY);
				Set<Profile> includeProfiles = getProfiles(binder, INCLUDE_PROFILES_PROPERTY);
				return new Document(propertySource, profiles, activeProfiles, includeProfiles);
			}).collect(Collectors.toList());
		}

		private StringBuilder getDescription(String prefix, String location, Resource resource, Profile profile) {
			StringBuilder result = new StringBuilder(prefix);
			try {
				if (resource != null) {
					String uri = resource.getURI().toASCIIString();
					result.append("'");
					result.append(uri);
					result.append("' (");
					result.append(location);
					result.append(")");
				}
			}
			catch (IOException ex) {
				result.append(location);
			}
			if (profile != null) {
				result.append(" for profile ");
				result.append(profile);
			}
			return result;
		}

		private Set<Profile> getProfiles(Binder binder, String name) {
			return binder.bind(name, STRING_ARRAY).map(this::asProfileSet).orElse(Collections.emptySet());
		}

		private Set<Profile> asProfileSet(String[] profileNames) {
			List<Profile> profiles = new ArrayList<>();
			for (String profileName : profileNames) {
				profiles.add(new Profile(profileName));
			}
			return new LinkedHashSet<>(profiles);
		}

		private void addProfileToEnvironment(String profile) {
			for (String activeProfile : this.environment.getActiveProfiles()) {
				if (activeProfile.equals(profile)) {
					return;
				}
			}
			this.environment.addActiveProfile(profile);
		}

		private Set<String> getSearchLocations() {
			Set<String> locations = getSearchLocations(CONFIG_ADDITIONAL_LOCATION_PROPERTY);
			// 自定义搜索位置 (配置 spring.config.location)
			if (this.environment.containsProperty(CONFIG_LOCATION_PROPERTY)) {
				locations.addAll(getSearchLocations(CONFIG_LOCATION_PROPERTY));
			}
			else {
				// 默认搜索位置 classpath:/,classpath:/config/,file:./,file:./config/*/,file:./config/
				locations.addAll(
						asResolvedSet(ConfigFileApplicationListener.this.searchLocations, DEFAULT_SEARCH_LOCATIONS));
			}
			return locations;
		}

		private Set<String> getSearchLocations(String propertyName) {
			Set<String> locations = new LinkedHashSet<>();
			if (this.environment.containsProperty(propertyName)) {
				for (String path : asResolvedSet(this.environment.getProperty(propertyName), null)) {
					if (!path.contains("$")) {
						path = StringUtils.cleanPath(path);
						Assert.state(!path.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX),
								"Classpath wildcard patterns cannot be used as a search location");
						validateWildcardLocation(path);
						if (!ResourceUtils.isUrl(path)) {
							path = ResourceUtils.FILE_URL_PREFIX + path;
						}
					}
					locations.add(path);
				}
			}
			return locations;
		}

		private void validateWildcardLocation(String path) {
			if (path.contains("*")) {
				Assert.state(StringUtils.countOccurrencesOf(path, "*") == 1,
						() -> "Search location '" + path + "' cannot contain multiple wildcards");
				String directoryPath = path.substring(0, path.lastIndexOf("/") + 1);
				Assert.state(directoryPath.endsWith("*/"), () -> "Search location '" + path + "' must end with '*/'");
			}
		}

		private Set<String> getSearchNames() {
			// 自定义配置文件 (配置 spring.config.name)
			if (this.environment.containsProperty(CONFIG_NAME_PROPERTY)) {
				String property = this.environment.getProperty(CONFIG_NAME_PROPERTY);
				Set<String> names = asResolvedSet(property, null);
				names.forEach(this::assertValidConfigName);
				return names;
			}
			// 替换占位符 或 返回默认配置文件 application
			return asResolvedSet(ConfigFileApplicationListener.this.names, DEFAULT_NAMES);
		}

		private Set<String> asResolvedSet(String value, String fallback) {
			List<String> list = Arrays.asList(StringUtils.trimArrayElements(StringUtils.commaDelimitedListToStringArray(
					(value != null) ? this.environment.resolvePlaceholders(value) : fallback)));
			Collections.reverse(list);
			return new LinkedHashSet<>(list);
		}

		private void assertValidConfigName(String name) {
			Assert.state(!name.contains("*"), () -> "Config name '" + name + "' cannot contain wildcards");
		}

		private void addLoadedPropertySources() {
			MutablePropertySources destination = this.environment.getPropertySources();
			List<MutablePropertySources> loaded = new ArrayList<>(this.loaded.values());
			// 反向排序(前置环境配置文件)
			Collections.reverse(loaded);
			String lastAdded = null;
			Set<String> added = new HashSet<>();
			for (MutablePropertySources sources : loaded) {
				for (PropertySource<?> source : sources) {
					// 去重
					if (added.add(source.getName())) {
						// 添加每个到 environment
						addLoadedPropertySource(destination, lastAdded, source);
						lastAdded = source.getName();
					}
				}
			}
		}

		private void addLoadedPropertySource(MutablePropertySources destination, String lastAdded,
											 PropertySource<?> source) {
			if (lastAdded == null) {
				if (destination.contains(DEFAULT_PROPERTIES)) {
					destination.addBefore(DEFAULT_PROPERTIES, source);
				}
				else {
					destination.addLast(source);
				}
			}
			else {
				destination.addAfter(lastAdded, source);
			}
		}

		private void applyActiveProfiles(PropertySource<?> defaultProperties) {
			List<String> activeProfiles = new ArrayList<>();
			if (defaultProperties != null) {
				Binder binder = new Binder(ConfigurationPropertySources.from(defaultProperties),
						new PropertySourcesPlaceholdersResolver(this.environment));
				activeProfiles.addAll(getDefaultProfiles(binder, "spring.profiles.include"));
				if (!this.activatedProfiles) {
					activeProfiles.addAll(getDefaultProfiles(binder, "spring.profiles.active"));
				}
			}
			this.processedProfiles.stream().filter(this::isDefaultProfile).map(Profile::getName)
					.forEach(activeProfiles::add);
			this.environment.setActiveProfiles(activeProfiles.toArray(new String[0]));
		}

		private boolean isDefaultProfile(Profile profile) {
			return profile != null && !profile.isDefaultProfile();
		}

		private List<String> getDefaultProfiles(Binder binder, String property) {
			return binder.bind(property, STRING_LIST).orElse(Collections.emptyList());
		}

	}

	/**
	 * A Spring Profile that can be loaded.
	 */
	private static class Profile {

		private final String name;

		private final boolean defaultProfile;

		Profile(String name) {
			this(name, false);
		}

		Profile(String name, boolean defaultProfile) {
			Assert.notNull(name, "Name must not be null");
			this.name = name;
			this.defaultProfile = defaultProfile;
		}

		String getName() {
			return this.name;
		}

		boolean isDefaultProfile() {
			return this.defaultProfile;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (obj == null || obj.getClass() != getClass()) {
				return false;
			}
			return ((Profile) obj).name.equals(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.hashCode();
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * Cache key used to save loading the same document multiple times.
	 */
	private static class DocumentsCacheKey {

		private final PropertySourceLoader loader;

		private final Resource resource;

		DocumentsCacheKey(PropertySourceLoader loader, Resource resource) {
			this.loader = loader;
			this.resource = resource;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			DocumentsCacheKey other = (DocumentsCacheKey) obj;
			return this.loader.equals(other.loader) && this.resource.equals(other.resource);
		}

		@Override
		public int hashCode() {
			return this.loader.hashCode() * 31 + this.resource.hashCode();
		}

	}

	/**
	 * A single document loaded by a {@link PropertySourceLoader}.
	 */
	private static class Document {

		private final PropertySource<?> propertySource;

		private String[] profiles;

		private final Set<Profile> activeProfiles;

		private final Set<Profile> includeProfiles;

		Document(PropertySource<?> propertySource, String[] profiles, Set<Profile> activeProfiles,
				 Set<Profile> includeProfiles) {
			this.propertySource = propertySource;
			this.profiles = profiles;
			this.activeProfiles = activeProfiles;
			this.includeProfiles = includeProfiles;
		}

		PropertySource<?> getPropertySource() {
			return this.propertySource;
		}

		String[] getProfiles() {
			return this.profiles;
		}

		Set<Profile> getActiveProfiles() {
			return this.activeProfiles;
		}

		Set<Profile> getIncludeProfiles() {
			return this.includeProfiles;
		}

		@Override
		public String toString() {
			return this.propertySource.toString();
		}

	}

	/**
	 * Factory used to create a {@link DocumentFilter}.
	 */
	@FunctionalInterface
	private interface DocumentFilterFactory {

		/**
		 * Create a filter for the given profile.
		 * @param profile the profile or {@code null}
		 * @return the filter
		 */
		DocumentFilter getDocumentFilter(Profile profile);

	}

	/**
	 * Filter used to restrict when a {@link Document} is loaded.
	 */
	@FunctionalInterface
	private interface DocumentFilter {

		boolean match(Document document);

	}

	/**
	 * Consumer used to handle a loaded {@link Document}.
	 * 回调处理 Document
	 */
	@FunctionalInterface
	private interface DocumentConsumer {

		void accept(Profile profile, Document document);

	}

}
