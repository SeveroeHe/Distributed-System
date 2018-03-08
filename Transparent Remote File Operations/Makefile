PROGS= server
CFLAGS+=-Wall 
##-I../include -L../lib
all: mylib.so $(PROGS)

server: server.o 
	gcc -I../include -L../lib server.o -ldirtree -o server 
server.o: server.c
	gcc -Wall -g -c server.c


mylib.o: mylib.c 
	gcc -Wall -fPIC -DPIC -c -g mylib.c 
mylib.so: mylib.o
	ld -shared -I../include -L../lib -ldirtree -o mylib.so mylib.o -ldl

clean:
	rm -f *.o *.so $(PROGS)


