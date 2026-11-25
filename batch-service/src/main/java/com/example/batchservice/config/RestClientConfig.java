package com.example.batchservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class RestClientConfig {

    @Bean
    ClientHttpRequestFactory clientHttpRequestFactory(ServicesProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.http().connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.http().readTimeout());
        return factory;
    }

    @Bean
    RestClient userRestClient(RestClient.Builder builder, ClientHttpRequestFactory factory, ServicesProperties props) {
        return builder.clone().requestFactory(factory).baseUrl(props.user().url()).build();
    }

    @Bean
    RestClient orderRestClient(RestClient.Builder builder, ClientHttpRequestFactory factory, ServicesProperties props) {
        return builder.clone().requestFactory(factory).baseUrl(props.order().url()).build();
    }

    @Bean
    RestClient paymentRestClient(RestClient.Builder builder, ClientHttpRequestFactory factory, ServicesProperties props) {
        return builder.clone().requestFactory(factory).baseUrl(props.payment().url()).build();
    }
}
