FROM nginx:1.27-alpine
COPY index.html app.js styles.css /usr/share/nginx/html/
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
# Restrict substitution to our one configurable value, preserving Nginx variables.
ENV BACKEND_ORIGIN=http://backend:8080 NGINX_ENVSUBST_FILTER=BACKEND_ORIGIN
EXPOSE 80
