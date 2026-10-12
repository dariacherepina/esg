# Static UI served by nginx, which also forwards /api/... to the four services.
# Not one of the measured services in the experiments.
#
# Build from the repo root:
#   docker build -f frontend/Dockerfile -t esg-frontend .

FROM nginx:1.27-alpine

# Defaults: services running as JARs on the host (Docker Desktop resolves host.docker.internal).
# Override with -e when the services run as containers, e.g. COMPANY_SERVICE_URL=http://cs:8081
ENV COMPANY_SERVICE_URL=http://host.docker.internal:8081 \
    SCORE_SERVICE_URL=http://host.docker.internal:8082 \
    PORTFOLIO_SERVICE_URL=http://host.docker.internal:8083 \
    REPORT_SERVICE_URL=http://host.docker.internal:8084

COPY frontend/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY frontend/index.html frontend/styles.css frontend/app.js /usr/share/nginx/html/

EXPOSE 8080
