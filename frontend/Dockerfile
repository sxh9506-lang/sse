FROM nginx:1.27-alpine
COPY index.html app.js styles.css /usr/share/nginx/html/
RUN test -s /usr/share/nginx/html/index.html \
    && test -s /usr/share/nginx/html/app.js \
    && test -s /usr/share/nginx/html/styles.css
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# Restrict substitution to our one configurable value, preserving Nginx variables.
ENV BACKEND_ORIGIN=http://backend:8080 NGINX_ENVSUBST_FILTER=BACKEND_ORIGIN
EXPOSE 80
