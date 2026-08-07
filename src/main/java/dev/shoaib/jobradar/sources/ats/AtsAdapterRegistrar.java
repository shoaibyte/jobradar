package dev.shoaib.jobradar.sources.ats;

import dev.shoaib.jobradar.core.CompanyEntry;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers one Spring bean per company in {@code config/companies.yml} whose {@code ats} is
 * greenhouse/personio/lever, so each shows up individually (e.g. {@code "greenhouse:paypay"})
 * in the pipeline's {@code List<JobSourceAdapter>} autowiring, per Section 4.2/4.3/4.5.
 *
 * <p>This has to happen at bean-*definition* time (before regular singletons are instantiated),
 * which is why it's an {@link ImportBeanDefinitionRegistrar} rather than a plain {@code @Bean}
 * factory method -- a {@code @Bean List<JobSourceAdapter>} method would produce a single bean of
 * type {@code List}, which does NOT get flattened into other components' {@code List<JobSourceAdapter>}
 * autowiring (Spring only aggregates beans individually assignable to the element type).
 *
 * <p>Reads {@code config/companies.yml} itself (via {@link CompanyYamlLoader}, the same loader
 * {@link YamlCompanyRegistry} uses) rather than depending on the {@code CompanyRegistry} bean,
 * to avoid bean-creation-order coupling between a {@code BeanDefinitionRegistryPostProcessor}
 * style component and a normal singleton.
 */
public class AtsAdapterRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(AtsAdapterRegistrar.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        List<CompanyEntry> companies = loadCompanies();

        String greenhouseBaseUrl = environment.getProperty(
            "app.sources.greenhouse.base-url", "https://boards-api.greenhouse.io/v1/boards");
        boolean greenhouseEnabled = environment.getProperty(
            "app.sources.greenhouse.enabled", Boolean.class, true);

        String personioTemplate = environment.getProperty(
            "app.sources.personio.base-url-template", "https://{sub}.jobs.personio.de/xml?language=en");
        boolean personioEnabled = environment.getProperty(
            "app.sources.personio.enabled", Boolean.class, true);

        String leverBaseUrl = environment.getProperty(
            "app.sources.lever.base-url", "https://api.lever.co/v0/postings");
        boolean leverEnabled = environment.getProperty(
            "app.sources.lever.enabled", Boolean.class, false);

        int registered = 0;
        for (CompanyEntry entry : companies) {
            if (entry.token() == null || entry.token().isBlank()) {
                continue;
            }
            String ats = entry.ats() == null ? "" : entry.ats().toLowerCase();
            switch (ats) {
                case "greenhouse" -> {
                    registerAdapter(registry, GreenhouseAdapter.class,
                        "greenhouseAdapter-" + entry.token(), entry, greenhouseBaseUrl, greenhouseEnabled);
                    registered++;
                }
                case "personio" -> {
                    registerAdapter(registry, PersonioAdapter.class,
                        "personioAdapter-" + entry.token(), entry, personioTemplate, personioEnabled);
                    registered++;
                }
                case "lever" -> {
                    registerAdapter(registry, LeverAdapter.class,
                        "leverAdapter-" + entry.token(), entry, leverBaseUrl, leverEnabled);
                    registered++;
                }
                default -> {
                    // custom-html and anything else belongs to sources-html, not us.
                }
            }
        }
        log.info("Registered {} ATS adapter bean(s) from companies.yml", registered);
    }

    private void registerAdapter(BeanDefinitionRegistry registry, Class<?> adapterClass, String beanName,
        CompanyEntry entry, String urlConfig, boolean enabled) {
        ConstructorArgumentValues args = new ConstructorArgumentValues();
        args.addIndexedArgumentValue(0, entry);
        args.addIndexedArgumentValue(1, urlConfig);
        args.addIndexedArgumentValue(2, enabled);

        GenericBeanDefinition bd = new GenericBeanDefinition();
        bd.setBeanClass(adapterClass);
        bd.setConstructorArgumentValues(args);
        registry.registerBeanDefinition(beanName, bd);
    }

    private List<CompanyEntry> loadCompanies() {
        String companiesFile = environment.getProperty("app.companies-file", "file:config/companies.yml");
        Resource resource = new DefaultResourceLoader().getResource(companiesFile);
        try (InputStream in = resource.getInputStream()) {
            return CompanyYamlLoader.load(in);
        } catch (IOException e) {
            log.warn("Could not load {} while registering ATS adapters -- no greenhouse/personio/lever "
                + "beans will be created for this run: {}", companiesFile, e.toString());
            return List.of();
        }
    }
}
