FROM  node:24.13.0-alpine3.23

COPY . /app

WORKDIR /app

RUN yarn install && \
  yarn build

CMD [ "yarn", "preview", "--host" ]
