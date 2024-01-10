# Use this to run the Chisel notebooks

# temporary fix from https://github.com/freechipsproject/chisel-bootcamp/issues/140
FROM ucbbar/chisel-bootcamp:latest

RUN cp -r /coursier_cache /home/bootcamp/coursier_cache
ENV COURSIER_CACHE=/home/bootcamp/coursier_cache

# add our files in
USER root
ADD . /chisel-bootcamp
RUN chown -R bootcamp:bootcamp /chisel-bootcamp

USER bootcamp
WORKDIR /chisel-bootcamp

EXPOSE 8888
CMD jupyter notebook --no-browser --ip 0.0.0.0 --port 8888
